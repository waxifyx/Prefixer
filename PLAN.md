# Prefixer Plan

## Core decisions

- Prefixer storage is source of truth.
- LuckPerms is compatibility sink only.
- Prefixer stores rich typed prefix and suffix data:
  - `text`
  - `raw`
  - `image`
  - `none`
- When Prefixer prefix or suffix is unset, Prefixer placeholders fall back to existing LuckPerms prefix or suffix that was not set by Prefixer.
- When Prefixer prefix or suffix is set to `image`, LuckPerms gets fallback text only.
- When Prefixer prefix or suffix is set to `text`, LuckPerms gets formatted fallback text.
- When Prefixer prefix or suffix is set to `raw`, LuckPerms gets raw text as fallback value.
- When Prefixer prefix or suffix is set to `none`, LuckPerms node set to empty compatibility value or removed, based on final hook design.
- Server owners must use Prefixer placeholders instead of LuckPerms placeholders if they want image prefixes.

## Feature scope

### Commands

- `/setprefix [player] [type] <prefix> [fallback]`
- `/setsuffix [player] [type] <suffix> [fallback]`
- `player` defaults to self when sender is player.
- `type` defaults to `text`.
- `fallback` required for `image`.
- `type` values first pass:
  - `text`
  - `raw`
  - `image`
  - `none`

### Configurable messages

- Add `messages.yml` for all player-facing strings.
- Message values are MiniMessage strings.
- Messages should support MiniPlaceholders and Prefixer-provided dynamic values.

### Discord

- JDA-backed slash commands for prefix and suffix management.
- Role whitelist and user whitelist.
- Image mode supports Discord attachment upload directly.

### Placeholders

- PlaceholderAPI expansion for:
  - `%prefixer_prefix%`
  - `%prefixer_suffix%`
  - `%prefixer_prefix_fallback%`
  - `%prefixer_suffix_fallback%`
- MiniPlaceholders expansion for use inside MiniMessage config and messages.
- Prefixer MiniPlaceholders namespace should expose same logical values in component-safe form.

## Data model

## Enums

- `TagKind`: `PREFIX`, `SUFFIX`
- `TagType`: `TEXT`, `RAW`, `IMAGE`, `NONE`

## Stored records

### PlayerTagRecord

- `UUID playerId`
- `TagKind kind`
- `TagType type`
- `String value`
- `String fallback`
- `Instant updatedAt`
- `UUID updatedBy`

Notes:

- `value` for `text`: user-entered text before format wrapping.
- `value` for `raw`: raw MiniMessage or raw literal, based on final parser rules.
- `value` for `image`: stable image id or serialized chunk reference key, not raw file path.
- `value` for `none`: empty string.

### ImageAssetRecord

- `String imageId`
- `int width`
- `int height`
- `String sourceHint`
- `Instant createdAt`
- `List<ImageChunkRecord> chunks`

### ImageChunkRecord

- `String hash`
- `int index`
- `String textureValue`
- `String textureSignature`

Notes:

- Chunks are `8x8`.
- Image width must be `8 * n`.
- Image height must be `8`.
- Hash dedupes across all images.

## Rendering rules

### Text

- Parse user text with MiniMessage.
- Apply configured format:
  - `prefix.format` wraps `{prefix}`
  - `suffix.format` wraps `{suffix}`
- Message and config parsing should include MiniPlaceholders audience resolver.

### Raw

- Bypass `prefix.format` and `suffix.format`.
- Parse as direct MiniMessage, with MiniPlaceholders enabled.

### Image

- Render component from ordered sequence of player-head object components.
- Each chunk becomes one `Component.object(PlayerHeadObjectContents)`.
- Always render with `hat(true)`.
- Fallback text stored and used for LuckPerms compatibility and fallback placeholders.

### None

- Prefixer component resolves to empty.
- Placeholder fallback behavior still checks external LuckPerms value if Prefixer record is absent.

## LuckPerms integration

## Rules

- LuckPerms is required dependency.
- Prefixer should not try to serialize object components into LuckPerms.
- Prefixer writes only compatibility string to LuckPerms.
- Prefixer reads existing LuckPerms prefix or suffix when Prefixer has no stored value.

## Write strategy

- On Prefixer set operation:
  - compute compatibility string
  - replace Prefixer-managed LuckPerms node for matching kind
- Preserve non-Prefixer-managed LuckPerms data when Prefixer has no stored override.

## Safe ownership plan

- Prefixer-managed LuckPerms nodes need marker metadata if possible, else dedicated weight strategy and exact string matching cache.
- Best path: persist last written compatibility string in Prefixer storage and only remove or replace that exact previous value.
- Do not blanket-clear all user prefix or suffix nodes.

## Placeholder behavior

### `%prefixer_prefix%`

- If Prefixer prefix exists:
  - `text`: return rendered Prefixer prefix
  - `raw`: return rendered raw component/string
  - `image`: return rendered image component
  - `none`: return empty
- Else return current LuckPerms prefix.

### `%prefixer_suffix%`

- Same rule as prefix.

### `%prefixer_prefix_fallback%`

- If Prefixer prefix exists and fallback exists, return fallback.
- Else return current LuckPerms prefix.

### `%prefixer_suffix_fallback%`

- Same rule as prefix.

## MiniPlaceholders plan

Use MiniPlaceholders for:

- messages
- config-defined format strings
- raw and text parsing where audience-aware placeholders useful

Register Prefixer expansion, likely namespace `prefixer`, with audience placeholders:

- `<prefixer_prefix>`
- `<prefixer_suffix>`
- `<prefixer_prefix_fallback>`
- `<prefixer_suffix_fallback>`

Implementation note from docs:

- MiniPlaceholders supports `Expansion.builder("name")`
- supports `audiencePlaceholder(...)`
- supports `globalPlaceholder(...)`
- use `MiniPlaceholders.audiencePlaceholders()` during MiniMessage parsing

## Image handling plan

## Accepted sources

- absolute path
- relative path
- URL
- Discord attachment

## Validation

- height must equal `8`
- width must be multiple of `8`
- reject any transparency
- use outer hat layer in rendered head objects
- decode only supported image formats first pass:
  - PNG
  - JPG or JPEG
  - WebP if decoder available, else postpone

## Processing

1. Load bytes.
2. Decode image.
3. Validate dimensions and alpha.
4. Split into `8x8` chunks.
5. Hash each chunk.
6. Reuse cached MineSkin textures by hash.
7. Upload only missing chunks.
8. Store resulting image asset and player tag record.

## MineSkin plan

- Use MineSkin V2 queue flow.
- Reuse RickRoll design ideas:
  - API key rotation
  - low concurrency
  - retry and rate-limit handling
  - hash-based dedupe
- Request flow:
  - `POST /v2/queue`
  - poll `GET /v2/queue/:jobId`
- Parse texture data from:
  - `skin.texture.data.value`
  - `skin.texture.data.signature`

## CommandAPI plan

CommandAPI docs support:

- builder-style registration with `CommandAPICommand`
- argument access by node name with `CommandArguments`
- optional arguments with `getOrDefault` and `getOptional`

Use CommandAPI for:

- `/setprefix`
- `/setsuffix`
- `/prefixer reload`

Argument shape recommendation:

- `player`: optional `EntitySelectorArgument.OnePlayer`
- `type`: optional `MultiLiteralArgument` or string suggestions
- `value`: `GreedyStringArgument`
- `fallback`: second command branch for image mode, not ambiguous greedy tail in same branch

Reason:

- one greedy argument swallows tail.
- image mode needs distinct parsing path.

Recommended command tree:

- `/setprefix`
  - `<value>`
  - `<type> <value>`
  - `<player> <value>`
  - `<player> <type> <value>`
  - `<type> <value> <fallback>` for image branch only
  - `<player> <type> <value> <fallback>` for image branch only

May simplify by using explicit subcommands if parser gets ugly:

- `/setprefix text ...`
- `/setprefix raw ...`
- `/setprefix image ...`
- `/setprefix none [player]`

Need final choice during implementation.

## JDA plan

JDA docs support:

- slash commands via `Commands.slash(...)`
- typed options via `addOption(...)`
- attachment handling supported by attachment options and interaction attachments

Implement first pass Discord commands as slash commands only:

- `/setprefix`
- `/setsuffix`

Slash command options:

- `player` string or UUID-mapped Minecraft name
- `type` string choice
- `value` string
- `fallback` string optional
- `image` attachment optional

Validation rules:

- `image` attachment required for `image` type if no URL or path used
- whitelist by Discord user id or role id before any action

## Storage plan

Support both:

- JSON
- SQLite

Approach:

- Define storage interfaces first.
- JSON and SQLite backends implement same repository contracts.

Repositories:

- `PlayerTagRepository`
- `ImageAssetRepository`
- `ChunkTextureRepository`

JSON layout recommendation:

- `data/tags.json`
- `data/images.json`
- `data/chunks.json`

SQLite tables recommendation:

- `player_tags`
- `image_assets`
- `image_chunks`

## Permissions plan

Support exact permission family requested:

- `prefixer.setprefix.self`
- `prefixer.setprefix.self.text`
- `prefixer.setprefix.self.raw`
- `prefixer.setprefix.self.images`
- `prefixer.setprefix.others.text`
- `prefixer.setprefix.others.raw`
- `prefixer.setprefix.others.images`
- `prefixer.setsuffix.self`
- `prefixer.setsuffix.self.text`
- `prefixer.setsuffix.self.raw`
- `prefixer.setsuffix.self.images`
- `prefixer.setsuffix.others.text`
- `prefixer.setsuffix.others.raw`
- `prefixer.setsuffix.others.images`
- `prefixer.setprefix.cooldown.Ns|Nm|Nh|Nd|bypass`
- `prefixer.setsuffix.cooldown.Ns|Nm|Nh|Nd|bypass`
- `prefixer.setprefix.maxlength.N|bypass`
- `prefixer.setsuffix.maxlength.N|bypass`

Implementation rule:

- derive best effective cooldown and max-length from granted permissions at runtime.

## Config plan

Extend `config.yml`.

Add sections:

- `storage-method`
- `prefix.format`
- `prefix.default-cooldown`
- `prefix.max-length`
- `prefix.allowed-regex`
- `suffix.format`
- `suffix.default-cooldown`
- `suffix.max-length`
- `suffix.allowed-regex`
- `image.allowed-sources`
- `image.max-width`
- `discord.*`
- `mineskin.*`
- `miniplaceholders.enabled`
- `placeholderapi.enabled`

## Messages plan

Create `messages.yml` with keys for:

- no permission
- invalid player
- invalid type
- cooldown active
- max length exceeded
- regex failed
- image fallback missing
- image dimensions invalid
- image transparency denied
- storage error
- LuckPerms sync error
- Discord whitelist denied
- reload success
- set success self
- set success other
- cleared self
- cleared other

## Recommended implementation order

1. Add dependencies and bootstrap wiring.
2. Add config and messages loading.
3. Add core models and storage interfaces.
4. Add JSON backend.
5. Add SQLite backend.
6. Add LuckPerms compatibility service.
7. Add component rendering service.
8. Add PlaceholderAPI expansion.
9. Add MiniPlaceholders expansion.
10. Add CommandAPI commands.
11. Add image loader and validator.
12. Add MineSkin client and chunk cache.
13. Add Discord JDA integration.
14. Add tests for parsing, validation, repository behavior, and LuckPerms compatibility logic.

## Risks

- LuckPerms ownership of prefix nodes must be handled carefully. Wrong clear logic can wipe unrelated admin-set values.
- PlaceholderAPI returns strings in many integrations. Component-safe image rendering may only work where Adventure components are respected.
- WebP decode may need extra library.
- Discord and server thread boundaries need strict async handling.
- MineSkin rate limits can make image operations slow.

## First implementation target

Build minimal vertical slice first:

- JSON storage only
- LuckPerms compatibility sync
- CommandAPI `/setprefix` and `/setsuffix`
- `text`, `raw`, `none` types only
- PlaceholderAPI and MiniPlaceholders support
- no Discord yet
- no image yet

Then second slice:

- image pipeline
- MineSkin cache
- Discord commands
- SQLite backend polish
