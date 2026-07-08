package xyz.waxify.prefixer.storage;

import xyz.waxify.prefixer.model.ImageTagChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public final class SqliteChunkCacheStorage implements ChunkCacheStorage {
    private final Connection connection;

    public SqliteChunkCacheStorage(Path path) {
        try {
            Files.createDirectories(path.getParent());
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS image_chunks (
                        hash TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        signature TEXT NOT NULL
                    )
                    """);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize SQLite chunk cache", ex);
        }
    }

    @Override
    public Optional<ImageTagChunk> find(String hash) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value, signature FROM image_chunks WHERE hash = ?")) {
            statement.setString(1, hash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ImageTagChunk(resultSet.getString("value"), resultSet.getString("signature")));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read SQLite chunk cache", ex);
        }
    }

    @Override
    public void save(String hash, ImageTagChunk chunk) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO image_chunks (hash, value, signature) VALUES (?, ?, ?)
            ON CONFLICT(hash) DO UPDATE SET value = excluded.value, signature = excluded.signature
            """)) {
            statement.setString(1, hash);
            statement.setString(2, chunk.value());
            statement.setString(3, chunk.signature());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to write SQLite chunk cache", ex);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
