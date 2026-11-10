package dev.chico.bot.discord;

import dev.chico.bot.application.AttachmentInput;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.entities.Message;

/** Maps image attachments to downloader inputs. Forged URLs are skipped here. */
final class AttachmentInputs {

  private AttachmentInputs() {
  }

  static List<AttachmentInput> fromAttachments(List<Message.Attachment> attachments) {
    List<AttachmentInput> inputs = new ArrayList<>();
    // POR QUE: no local limit here — ImportFromImage caps at 5 and counts the
    // rest as droppedImages for the preview footer. Truncating here would make
    // the dropped count unreachable (always 0).
    for (Message.Attachment attachment : attachments) {
      if (!attachment.isImage()) {
        continue;
      }
      URI uri;
      try {
        uri = new URI(attachment.getUrl());
      } catch (Exception e) {
        continue;
      }
      inputs.add(
          new AttachmentInput(
              uri,
              attachment.getContentType(),
              attachment.getSize(),
              attachment.getWidth(),
              attachment.getHeight()));
    }
    return inputs;
  }
}
