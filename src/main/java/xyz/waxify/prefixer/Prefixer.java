package xyz.waxify.prefixer;

import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIPaperConfig;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.waxify.prefixer.command.PrefixCommands;
import xyz.waxify.prefixer.config.PrefixerConfig;
import xyz.waxify.prefixer.discord.DiscordBot;
import xyz.waxify.prefixer.hook.MiniPlaceholdersHook;
import xyz.waxify.prefixer.hook.PlaceholderApiHook;
import xyz.waxify.prefixer.service.LuckPermsService;
import xyz.waxify.prefixer.service.MessageService;
import xyz.waxify.prefixer.service.MineSkinService;
import xyz.waxify.prefixer.service.PermissionService;
import xyz.waxify.prefixer.service.PrefixService;
import xyz.waxify.prefixer.service.TagRenderer;
import xyz.waxify.prefixer.service.ImageService;
import xyz.waxify.prefixer.storage.JsonTagStorage;
import xyz.waxify.prefixer.storage.JsonChunkCacheStorage;
import xyz.waxify.prefixer.storage.ChunkCacheStorage;
import xyz.waxify.prefixer.storage.SqliteChunkCacheStorage;
import xyz.waxify.prefixer.storage.SqliteTagStorage;
import xyz.waxify.prefixer.storage.TagStorage;
import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;

import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Prefixer extends JavaPlugin {
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<TagKey, AtomicLong> tagVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TagKey, CompletableFuture<Void>> mutationTails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> chunkLocks = new ConcurrentHashMap<>();
    private volatile PrefixerConfig pluginConfig;
    private MessageService messages;
    private TagStorage tagStorage;
    private ChunkCacheStorage chunkCacheStorage;
    private TagRenderer tagRenderer;
    private volatile PrefixService prefixService;
    private PlaceholderApiHook placeholderApiHook;
    private MiniPlaceholdersHook miniPlaceholdersHook;
    private volatile ImageService imageService;
    private DiscordBot discordBot;

    @Override
    public void onLoad() {
        CommandAPI.onLoad(new CommandAPIPaperConfig(this).silentLogs(true));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().toPath().resolve("messages.yml").toFile().exists()) {
            saveResource("messages.yml", false);
        }
        reloadPluginState();
        CommandAPI.onEnable();
        PrefixCommands.register(this, prefixService, messages);
        reconcileHooks();
    }

    @Override
    public void onDisable() {
        final DiscordBot bot;
        stateLock.writeLock().lock();
        try {
            if (placeholderApiHook != null) {
                placeholderApiHook.unregister();
            }
            if (miniPlaceholdersHook != null) {
                miniPlaceholdersHook.unregister();
                miniPlaceholdersHook = null;
            }
            if (tagStorage != null) {
                tagStorage.close();
            }
            if (chunkCacheStorage != null) {
                chunkCacheStorage.close();
            }
            bot = discordBot;
            discordBot = null;
        } finally {
            stateLock.writeLock().unlock();
        }
        // Stopped outside the state lock so shutdown never stalls state readers.
        if (bot != null) {
            bot.stop();
        }
        CommandAPI.onDisable();
    }

    public void reloadPluginState() {
        final DiscordBot previousBot;
        final DiscordBot nextBot;
        stateLock.writeLock().lock();
        try {
            reloadConfig();
            pluginConfig = PrefixerConfig.load(getConfig());

            if (messages == null) {
                messages = new MessageService(this);
            }
            messages.reload();

            TagStorage newTagStorage = switch (pluginConfig.storageMethod()) {
                case JSON -> new JsonTagStorage(getDataFolder().toPath().resolve("data/tags.json"));
                case SQLITE -> new SqliteTagStorage(getDataFolder().toPath().resolve("data/prefixer.db"));
            };
            ChunkCacheStorage newChunkCacheStorage;
            try {
                newChunkCacheStorage = switch (pluginConfig.storageMethod()) {
                    case JSON -> new JsonChunkCacheStorage(getDataFolder().toPath().resolve("data/chunks.json"));
                    case SQLITE -> new SqliteChunkCacheStorage(getDataFolder().toPath().resolve("data/prefixer.db"));
                };
            } catch (RuntimeException ex) {
                newTagStorage.close();
                throw ex;
            }
            if (tagStorage != null) {
                tagStorage.close();
            }
            if (chunkCacheStorage != null) {
                chunkCacheStorage.close();
            }
            tagStorage = newTagStorage;
            chunkCacheStorage = newChunkCacheStorage;

            tagRenderer = new TagRenderer(this, pluginConfig, tagStorage);
            LuckPermsService luckPermsService = new LuckPermsService(this);
            prefixService = new PrefixService(
                this,
                pluginConfig,
                tagStorage,
                tagRenderer,
                luckPermsService,
                new PermissionService(pluginConfig),
                messages
            );
            prefixService.reconcileLuckPermsState();
            imageService = new ImageService(this, new MineSkinService(this), messages);
            reconcileHooks();

            // Publish the replacement under the lock; lifecycle transitions run after unlock.
            previousBot = discordBot;
            nextBot = new DiscordBot(this);
            discordBot = nextBot;
        } finally {
            stateLock.writeLock().unlock();
        }
        if (previousBot != null) {
            previousBot.stop();
        }
        nextBot.start();
    }

    private void reconcileHooks() {
        boolean placeholderApiAvailable = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (pluginConfig.placeholderApiEnabled() && placeholderApiAvailable && placeholderApiHook == null) {
            placeholderApiHook = new PlaceholderApiHook(this);
            placeholderApiHook.register();
        } else if ((!pluginConfig.placeholderApiEnabled() || !placeholderApiAvailable) && placeholderApiHook != null) {
            placeholderApiHook.unregister();
            placeholderApiHook = null;
        }

        if (pluginConfig.miniPlaceholdersEnabled()
            && getServer().getPluginManager().getPlugin("MiniPlaceholders") != null
            && miniPlaceholdersHook == null) {
            miniPlaceholdersHook = new MiniPlaceholdersHook(this);
            miniPlaceholdersHook.register();
        } else if ((!pluginConfig.miniPlaceholdersEnabled()
            || getServer().getPluginManager().getPlugin("MiniPlaceholders") == null)
            && miniPlaceholdersHook != null) {
            miniPlaceholdersHook.unregister();
            miniPlaceholdersHook = null;
        }
    }

    public PrefixerConfig pluginConfig() {
        return pluginConfig;
    }

    public MessageService messages() {
        return messages;
    }

    public PrefixService prefixService() {
        return prefixService;
    }

    public ImageService imageService() {
        return imageService;
    }

    public <T> T withChunkCache(Function<ChunkCacheStorage, T> action) {
        return withStateRead(() -> action.apply(chunkCacheStorage));
    }

    public <T> T withStateRead(Supplier<T> action) {
        stateLock.readLock().lock();
        try {
            return action.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public Object chunkLock(String hash) {
        return chunkLocks.computeIfAbsent(hash, ignored -> new Object());
    }

    public long beginImageEdit(UUID playerId, xyz.waxify.prefixer.model.TagKind kind) {
        return beginTagEdit(playerId, kind);
    }

    public long beginTagEdit(UUID playerId, TagKind kind) {
        return tagVersion(playerId, kind).incrementAndGet();
    }

    public boolean isCurrentImageEdit(UUID playerId, TagKind kind, long version) {
        return tagVersion(playerId, kind).get() == version;
    }

    public CompletableFuture<Boolean> enqueueTagMutation(UUID playerId, TagKind kind, Supplier<CompletableFuture<Boolean>> mutation) {
        TagKey key = new TagKey(playerId, kind);
        CompletableFuture<Boolean> result;
        CompletableFuture<Void> tail;
        synchronized (mutationTails) {
            CompletableFuture<Void> previous = mutationTails.getOrDefault(key, CompletableFuture.completedFuture(null));
            result = previous.handle((ignored, failure) -> null).thenCompose(ignored -> mutation.get());
            tail = result.handle((ignored, failure) -> null);
            mutationTails.put(key, tail);
        }
        tail.whenComplete((ignored, failure) -> mutationTails.remove(key, tail));
        return result;
    }

    public Optional<StoredTag> findCurrentTag(UUID playerId, TagKind kind) {
        return withStateRead(() -> tagStorage.find(playerId, kind));
    }

    public void saveCurrentTag(StoredTag tag) {
        withStateRead(() -> {
            tagStorage.save(tag);
            return null;
        });
    }

    public <T> CompletableFuture<T> runOnServerThread(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        getServer().getScheduler().runTask(this, () -> {
            try {
                result.complete(action.get());
            } catch (Exception ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    private AtomicLong tagVersion(UUID playerId, xyz.waxify.prefixer.model.TagKind kind) {
        return tagVersions.computeIfAbsent(new TagKey(playerId, kind), ignored -> new AtomicLong());
    }

    private record TagKey(UUID playerId, xyz.waxify.prefixer.model.TagKind kind) {
    }
}
