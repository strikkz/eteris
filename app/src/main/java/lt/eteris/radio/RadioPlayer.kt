package lt.eteris.radio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Radio-specific behaviour on top of the real player:
 * - "Next" / "Previous" (car buttons, steering wheel, notification) always switch the station, in a circle.
 * - After a long pause, playback resumes from the live broadcast, not from the old buffer.
 * Podcast episodes are ordinary recordings: time bar, rewinding; "next/previous" jump 15 seconds forward / back.
 */
@OptIn(UnstableApi::class)
class RadioPlayer(player: Player) : ForwardingPlayer(player) {

    private var pausedAt = 0L

    private val episode: Boolean get() = PodcastRepository.isEpisode(currentMediaItem?.mediaId)

    override fun getAvailableCommands(): Player.Commands =
        if (episode) super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
            .build()
        else super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            // Live radio: no rewinding inside a station.
            .removeAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
            .build()

    // Live broadcast has no length and no position: Android Auto and the notification then hide the time bar
    // (they showed "0:41 / 0:00" before). -1 is Android's "position unknown".
    override fun isCurrentMediaItemLive() = if (episode) super.isCurrentMediaItemLive() else true
    override fun isCurrentMediaItemSeekable() = if (episode) super.isCurrentMediaItemSeekable() else false
    override fun isCurrentMediaItemDynamic() = if (episode) super.isCurrentMediaItemDynamic() else true
    override fun getDuration(): Long = if (episode) super.getDuration() else C.TIME_UNSET
    override fun getContentDuration(): Long = if (episode) super.getContentDuration() else C.TIME_UNSET
    override fun getCurrentPosition(): Long = if (episode) super.getCurrentPosition() else POSITION_UNKNOWN
    override fun getContentPosition(): Long = if (episode) super.getContentPosition() else POSITION_UNKNOWN
    override fun getBufferedPosition(): Long = if (episode) super.getBufferedPosition() else 0
    override fun getContentBufferedPosition(): Long = if (episode) super.getContentBufferedPosition() else 0
    override fun getTotalBufferedDuration(): Long = if (episode) super.getTotalBufferedDuration() else 0
    override fun getCurrentLiveOffset(): Long = if (episode) super.getCurrentLiveOffset() else C.TIME_UNSET
    override fun getSeekBackIncrement(): Long = SKIP_BACK_MS
    override fun getSeekForwardIncrement(): Long = SKIP_FORWARD_MS
    override fun seekBack() = seekBy(-SKIP_BACK_MS)
    override fun seekForward() = seekBy(SKIP_FORWARD_MS)

    private fun seekBy(delta: Long) {
        if (!episode) return
        val dur = super.getDuration()
        var target = (super.getCurrentPosition() + delta).coerceAtLeast(0L)
        if (dur != C.TIME_UNSET && dur > 0) target = target.coerceAtMost(dur - 1000L)
        seekTo(target)
    }

    companion object {
        private const val POSITION_UNKNOWN = -1L
        const val SKIP_BACK_MS = 15_000L
        const val SKIP_FORWARD_MS = 15_000L
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = episode || mediaItemCount > 1
    override fun hasPreviousMediaItem(): Boolean = episode || mediaItemCount > 1

    // Episode: the steering-wheel / headset buttons skip inside the recording instead.
    override fun seekToNext() = if (episode) seekBy(SKIP_FORWARD_MS) else step(1)
    override fun seekToPrevious() = if (episode) seekBy(-SKIP_BACK_MS) else step(-1)
    override fun seekToNextMediaItem() = if (episode) seekBy(SKIP_FORWARD_MS) else step(1)
    override fun seekToPreviousMediaItem() = if (episode) seekBy(-SKIP_BACK_MS) else step(-1)

    private fun step(direction: Int) {
        val count = mediaItemCount
        if (count == 0) return
        val index = ((currentMediaItemIndex + direction) % count + count) % count
        seekToDefaultPosition(index)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }

    override fun pause() {
        pausedAt = SystemClock.elapsedRealtime()
        super.pause()
    }

    override fun play() {
        jumpToLiveIfPausedLong()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) jumpToLiveIfPausedLong() else pausedAt = SystemClock.elapsedRealtime()
        super.setPlayWhenReady(playWhenReady)
    }

    private fun jumpToLiveIfPausedLong() {
        if (episode) { pausedAt = 0; return }
        if (pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 15_000 && mediaItemCount > 0) {
            seekToDefaultPosition()
        }
        pausedAt = 0
    }
}
