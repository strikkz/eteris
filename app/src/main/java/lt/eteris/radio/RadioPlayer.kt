package lt.eteris.radio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Radio-specific behaviour on top of the real player:
 * - "Next" / "Previous" (car buttons, steering wheel, notification) always switch the station, in a circle.
 * - After a long pause, playback resumes from the live broadcast, not from the old buffer.
 */
@OptIn(UnstableApi::class)
class RadioPlayer(player: Player) : ForwardingPlayer(player) {

    private var pausedAt = 0L

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = mediaItemCount > 1
    override fun hasPreviousMediaItem(): Boolean = mediaItemCount > 1

    override fun seekToNext() = step(1)
    override fun seekToPrevious() = step(-1)
    override fun seekToNextMediaItem() = step(1)
    override fun seekToPreviousMediaItem() = step(-1)

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
        if (pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 15_000 && mediaItemCount > 0) {
            seekToDefaultPosition()
        }
        pausedAt = 0
    }
}
