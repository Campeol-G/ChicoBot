package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.chico.bot.source.TrackCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-guild queue. The current track follows playback events; transitions
 * reenter through onTrackEnd, so state changes are guarded, not assumed.
 */
public final class TrackScheduler extends AudioEventAdapter {

  public enum LoopMode {
    OFF,
    SINGLE,
    ALL
  }

  /** Loop state for the queue embed: marked display position, 0 when none. */
  public record LoopView(LoopMode mode, int markedPosition) {
    public static LoopView off() {
      return new LoopView(LoopMode.OFF, 0);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(TrackScheduler.class);

  private final AudioPlayer player;
  private final TrackQueue<TrackCandidate> queue;
  private final Runnable onChange;
  private volatile TrackCandidate current;
  private volatile TrackCandidate pending;
  private volatile boolean advancing;

  private volatile LoopMode loopMode = LoopMode.OFF;
  private volatile int loopMarkedAdvances;
  private volatile long loopMarkedItemId;
  private final List<TrackCandidate> loopCycle = new ArrayList<>();
  private int loopCursor;

  public TrackScheduler(AudioPlayer player, int maxQueue) {
    this(player, maxQueue, () -> {
    });
  }

  public TrackScheduler(AudioPlayer player, int maxQueue, Runnable onChange) {
    this.player = player;
    this.queue = new TrackQueue<>(maxQueue);
    this.onChange = onChange;
  }

  public synchronized boolean queue(TrackCandidate candidate) {
    advancing = true;
    try {
      if (player.startTrack(candidate.track(), true)) {
        current = candidate;
        pending = candidate;
        appendToCycle(candidate);
        return true;
      }
    } finally {
      advancing = false;
    }
    boolean added = queue.add(candidate);
    if (added) {
      appendToCycle(candidate);
    }
    return added;
  }

  public synchronized void nextTrack() {
    if (advancing) {
      return;
    }
    Optional<TrackCandidate> next = queue.poll();
    if (next.isEmpty()) {
      player.stopTrack();
      current = null;
      pending = null;
      return;
    }
    pending = next.orElseThrow();
    advancing = true;
    try {
      player.startTrack(pending.track(), false);
    } finally {
      advancing = false;
    }
    current = pending;
  }

  /**
   * Drops the first {@code queueIndex} queued tracks and starts the next one,
   * atomically against track-end events. Index is zero-based into the queue.
   */
  public synchronized Optional<TrackCandidate> skipTo(int queueIndex) {
    for (int i = 0; i < queueIndex; i++) {
      queue.poll();
    }
    nextTrack();
    return current();
  }

  /**
   * Removes one queued track atomically against track-end events. Index is
   * zero-based into the queue.
   */
  public synchronized Optional<TrackCandidate> removeAt(int queueIndex) {
    List<TrackCandidate> queued = queue.snapshot();
    if (queueIndex < 0 || queueIndex >= queued.size()) {
      return Optional.empty();
    }
    TrackCandidate removed = queued.get(queueIndex);
    queue.removeAt(queueIndex);
    onRemoved(removed);
    return Optional.of(removed);
  }

  /** Stops playback and clears the queue atomically. */
  public synchronized void stopAndClear() {
    player.stopTrack();
    queue.clear();
    current = null;
    pending = null;
    clearLoop();
    changed();
  }

  public Optional<TrackCandidate> current() {
    return Optional.ofNullable(current);
  }

  public TrackQueue<TrackCandidate> queueView() {
    return queue;
  }

  public synchronized LoopView loopView() {
    if (loopMode != LoopMode.SINGLE) {
      return new LoopView(loopMode, 0);
    }
    return new LoopView(loopMode, loopMarkedAdvances + 1);
  }

  /** Loops whatever is playing now. */
  public synchronized Optional<TrackCandidate> loopCurrent() {
    if (current == null) {
      return Optional.empty();
    }
    armSingle(0, current.itemId());
    return current();
  }

  /** Loops the display position once playback naturally reaches it. */
  public synchronized Optional<TrackCandidate> loopAt(int displayPosition) {
    if (displayPosition == 1) {
      return loopCurrent();
    }
    List<TrackCandidate> queued = queue.snapshot();
    int index = displayPosition - 2;
    if (index < 0 || index >= queued.size()) {
      return Optional.empty();
    }
    TrackCandidate target = queued.get(index);
    armSingle(displayPosition - 1, target.itemId());
    return Optional.of(target);
  }

  /** Repeats the current+queued snapshot as one mutable cycle. */
  public synchronized Optional<Integer> loopAll() {
    if (current == null && queue.size() == 0) {
      return Optional.empty();
    }
    loopMode = LoopMode.ALL;
    loopCycle.clear();
    if (current != null) {
      loopCycle.add(current);
    }
    loopCycle.addAll(queue.snapshot());
    loopCursor = 0;
    return Optional.of(loopCycle.size());
  }

  public synchronized void clearLoop() {
    loopMode = LoopMode.OFF;
    loopMarkedAdvances = 0;
    loopMarkedItemId = 0;
    loopCycle.clear();
    loopCursor = 0;
  }

  private void armSingle(int advances, long itemId) {
    loopMode = LoopMode.SINGLE;
    loopMarkedAdvances = advances;
    loopMarkedItemId = itemId;
    loopCycle.clear();
    loopCursor = 0;
  }

  @Override
  public synchronized void onTrackStart(AudioPlayer player, AudioTrack track) {
    if (pending != null && pending.track() == track) {
      current = pending;
    }
    changed();
  }

  @Override
  public synchronized void onTrackEnd(
      AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
    if (pending != null && pending.track() != track) {
      return;
    }
    log.info(
        "track ended reason={} mayStartNext={} title={}",
        endReason, endReason.mayStartNext, track.getInfo().title);
    if (!endReason.mayStartNext) {
      current = null;
      pending = null;
      changed();
      return;
    }
    if (current != null && current.track() == track) {
      advance();
    }
  }

  @Override
  public synchronized void onTrackException(
      AudioPlayer player, AudioTrack track, FriendlyException exception) {
    log.warn("Track failed, skipping to next: {}", track.getInfo().title, exception);
    if (pending != null && pending.track() != track) {
      return;
    }
    if (current != null && current.track() == track) {
      if (loopMode == LoopMode.SINGLE) {
        clearLoop();
      } else if (loopMode == LoopMode.ALL) {
        removeFromCycle(current.itemId());
      }
      nextTrack();
      changed();
    }
  }

  private void changed() {
    try {
      onChange.run();
    } catch (RuntimeException ignored) {
      // A UI refresh must never break playback.
    }
  }

  private void advance() {
    if (loopMode == LoopMode.SINGLE) {
      if (loopMarkedAdvances > 0) {
        loopMarkedAdvances--;
        nextTrack();
        if (current == null) {
          clearLoop();
        }
      } else {
        replaySingle();
      }
      return;
    }
    if (loopMode == LoopMode.ALL) {
      nextTrack();
      if (current == null) {
        replayCycle();
      }
      return;
    }
    nextTrack();
  }

  private void replaySingle() {
    TrackCandidate looped = current;
    if (looped == null || looped.track() == null) {
      clearLoop();
      nextTrack();
      return;
    }
    startReplay(looped);
  }

  private void replayCycle() {
    while (!loopCycle.isEmpty()) {
      if (loopCursor >= loopCycle.size()) {
        loopCursor = 0;
      }
      TrackCandidate next = loopCycle.get(loopCursor);
      loopCursor++;
      if (next.track() == null) {
        removeFromCycle(next.itemId());
        continue;
      }
      startReplay(next);
      return;
    }
    clearLoop();
    nextTrack();
  }

  private void startReplay(TrackCandidate source) {
    TrackCandidate replay = source.withTrack(source.track().makeClone());
    pending = replay;
    advancing = true;
    try {
      player.startTrack(replay.track(), false);
    } finally {
      advancing = false;
    }
    current = replay;
  }

  private void appendToCycle(TrackCandidate candidate) {
    if (loopMode == LoopMode.ALL) {
      loopCycle.add(candidate);
    }
  }

  private void onRemoved(TrackCandidate removed) {
    if (loopMode == LoopMode.SINGLE && loopMarkedItemId == removed.itemId()) {
      clearLoop();
      return;
    }
    if (loopMode == LoopMode.ALL) {
      removeFromCycle(removed.itemId());
    }
  }

  private void removeFromCycle(long itemId) {
    int index = -1;
    for (int i = 0; i < loopCycle.size(); i++) {
      if (loopCycle.get(i).itemId() == itemId) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      return;
    }
    loopCycle.remove(index);
    if (index < loopCursor) {
      loopCursor--;
    }
    if (loopCursor >= loopCycle.size()) {
      loopCursor = 0;
    }
    if (loopCycle.isEmpty()) {
      clearLoop();
    }
  }
}
