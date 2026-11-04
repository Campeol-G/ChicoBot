package dev.chico.bot.application;

import java.net.URI;

public record AttachmentInput(URI url, String contentType, long size, int width, int height) {
}
