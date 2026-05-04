package org.assiscabron.vortexProxy.core.studio;

import java.util.UUID;

public record StudioLinkMessage(
    String type,        // "AUTH", "SYNC_FILE", "LINK_ACK", "ERROR", "PULL_FILES", "PULL_RESPONSE"
    String code,        // 6-digit link code
    String experienceId,
    String fileName,
    String content,     // Base64 encoded file content
    String path,        // Relative path
    java.util.List<StudioFileInfo> files
) {
    public record StudioFileInfo(String path, String content) {}
}
