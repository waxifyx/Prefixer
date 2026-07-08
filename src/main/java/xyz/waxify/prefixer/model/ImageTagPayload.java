package xyz.waxify.prefixer.model;

import java.util.List;

public record ImageTagPayload(int width, List<ImageTagChunk> chunks) {
}
