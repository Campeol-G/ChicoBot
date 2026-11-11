package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.chico.bot.source.TrackCandidate;

/** Recovery hook called on the Lavaplayer thread; must dispatch and return. */
public interface TrackFailureHandler {

  void onFailure(TrackScheduler scheduler, TrackCandidate failed, FriendlyException exception);
}
