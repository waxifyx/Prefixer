package xyz.waxify.prefixer.storage;

import xyz.waxify.prefixer.model.StoredTag;
import xyz.waxify.prefixer.model.TagKind;
import xyz.waxify.prefixer.model.TagType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteTagStorage implements TagStorage {
    private final Connection connection;

    public SqliteTagStorage(Path path) {
        try {
            Files.createDirectories(path.getParent());
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_tags (
                        player_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        type TEXT NOT NULL,
                        value TEXT NOT NULL,
                        fallback TEXT NOT NULL,
                        updated_by TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        last_luckperms_value TEXT NOT NULL,
                        PRIMARY KEY (player_id, kind)
                    )
                    """);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize SQLite tag storage", ex);
        }
    }

    @Override
    public Optional<StoredTag> find(UUID playerId, TagKind kind) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM player_tags WHERE player_id = ? AND kind = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, kind.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(resultSet));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read SQLite tag storage", ex);
        }
    }

    @Override
    public Iterable<StoredTag> all() {
        List<StoredTag> tags = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM player_tags");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tags.add(readRow(resultSet));
            }
            return tags;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read all SQLite tags", ex);
        }
    }

    @Override
    public void save(StoredTag tag) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_tags (player_id, kind, type, value, fallback, updated_by, updated_at, last_luckperms_value)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(player_id, kind) DO UPDATE SET
                type = excluded.type,
                value = excluded.value,
                fallback = excluded.fallback,
                updated_by = excluded.updated_by,
                updated_at = excluded.updated_at,
                last_luckperms_value = excluded.last_luckperms_value
            """)) {
            statement.setString(1, tag.playerId().toString());
            statement.setString(2, tag.kind().name());
            statement.setString(3, tag.type().name());
            statement.setString(4, tag.value());
            statement.setString(5, tag.fallback());
            statement.setString(6, tag.updatedBy().toString());
            statement.setLong(7, tag.updatedAtEpochMillis());
            statement.setString(8, tag.lastLuckPermsValue());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to write SQLite tag storage", ex);
        }
    }

    @Override
    public void delete(UUID playerId, TagKind kind) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM player_tags WHERE player_id = ? AND kind = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, kind.name());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete SQLite tag storage row", ex);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private StoredTag readRow(ResultSet resultSet) throws SQLException {
        return new StoredTag(
            UUID.fromString(resultSet.getString("player_id")),
            TagKind.valueOf(resultSet.getString("kind")),
            TagType.valueOf(resultSet.getString("type")),
            resultSet.getString("value"),
            resultSet.getString("fallback"),
            UUID.fromString(resultSet.getString("updated_by")),
            resultSet.getLong("updated_at"),
            resultSet.getString("last_luckperms_value")
        );
    }
}
