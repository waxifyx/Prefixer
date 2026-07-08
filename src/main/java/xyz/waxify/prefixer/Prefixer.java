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

public final class Prefixer extends JavaPlugin {
    private PrefixerConfig pluginConfig;
    private MessageService messages;
    private TagStorage tagStorage;
    private ChunkCacheStorage chunkCacheStorage;
    private TagRenderer tagRenderer;
    private PrefixService prefixService;
    private PlaceholderApiHook placeholderApiHook;
    private MiniPlaceholdersHook miniPlaceholdersHook;
    private ImageService imageService;
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
        registerHooks();
    }

    @Override
    public void onDisable() {
        if (placeholderApiHook != null) {
            placeholderApiHook.unregister();
        }
        if (tagStorage != null) {
            tagStorage.close();
        }
        if (chunkCacheStorage != null) {
            chunkCacheStorage.close();
        }
        if (discordBot != null) {
            discordBot.stop();
        }
        CommandAPI.onDisable();
    }

    public void reloadPluginState() {
        reloadConfig();
        pluginConfig = PrefixerConfig.load(getConfig());

        if (messages == null) {
            messages = new MessageService(this);
        }
        messages.reload();

        if (tagStorage != null) {
            tagStorage.close();
        }
        if (chunkCacheStorage != null) {
            chunkCacheStorage.close();
        }
        tagStorage = switch (pluginConfig.storageMethod()) {
            case JSON -> new JsonTagStorage(getDataFolder().toPath().resolve("data/tags.json"));
            case SQLITE -> new SqliteTagStorage(getDataFolder().toPath().resolve("data/prefixer.db"));
        };
        chunkCacheStorage = switch (pluginConfig.storageMethod()) {
            case JSON -> new JsonChunkCacheStorage(getDataFolder().toPath().resolve("data/chunks.json"));
            case SQLITE -> new SqliteChunkCacheStorage(getDataFolder().toPath().resolve("data/prefixer.db"));
        };

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
        imageService = new ImageService(this, new MineSkinService(this), messages, prefixService, chunkCacheStorage);

        if (discordBot != null) {
            discordBot.stop();
        }
        discordBot = new DiscordBot(this);
        discordBot.start();
    }

    private void registerHooks() {
        if (pluginConfig.placeholderApiEnabled() && getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderApiHook = new PlaceholderApiHook(this, prefixService);
            placeholderApiHook.register();
        }

        if (pluginConfig.miniPlaceholdersEnabled() && getServer().getPluginManager().getPlugin("MiniPlaceholders") != null) {
            miniPlaceholdersHook = new MiniPlaceholdersHook(this, prefixService);
            miniPlaceholdersHook.register();
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
}
