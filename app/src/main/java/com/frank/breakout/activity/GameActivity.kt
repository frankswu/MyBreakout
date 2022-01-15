package com.frank.breakout.activity

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.frank.breakout.controller.GameState
import com.frank.breakout.opengl.view.GameSurfaceView
import com.frank.breakout.opengl.base.SoundResources
import com.frank.breakout.opengl.base.TextResources


class GameActivity : AppCompatActivity() {


    // The Activity has one View, a GL surface.
    private var mGLView: GameSurfaceView? = null

    // Live game state.
    //
    // We could make this static and let it persist across game restarts.  This would avoid
    // some setup time when we leave and re-enter the game, but it also means that the
    // GameState will stay in memory even after the game is no longer running.  If GameState
    // holds references to other objects, such as this Activity, the GC will be unable to
    // discard those either.
    private var mGameState: GameState? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GameActivity onCreate")

        // Initialize data that depends on Android resources.
        SoundResources.initialize(this)
        val textConfig = TextResources.configure(this)
        mGameState = GameState()
        configureGameState()

        // Create a GLSurfaceView, and set it as the Activity's "content view".  This will
        // also create a GLSurfaceView.Renderer, which starts the Renderer thread.
        //
        // IMPORTANT: anything we have done up to this point -- notably, configuring GameState --
        // will be visible to the new Renderer thread.  However, any accesses to mutual state
        // after this point must be guarded with some form of synchronization.
        mGLView = GameSurfaceView(this, mGameState!!, textConfig)
        setContentView(mGLView)
    }

    override fun onPause() {
        /*
         * We must call the GLView's onPause() function when the framework tells us to pause.
         * We're also expected to deallocate any large OpenGL resources, though presumably
         * that just means our associated Bitmaps and FloatBuffers since the OpenGL goodies
         * themselves (e.g. programs) are discarded by the GLSurfaceView.
         *
         * Our GLSurfaceView's onPause() method will synchronously invoke the GameState's save()
         * function on the Renderer thread.  This will record the saved game into the storage
         * we provided when the object was constructed.
         */
        Log.d(TAG, "GameActivity pausing")
        super.onPause()
        mGLView?.onPause()

        /*
         * If the game is over, record the new high score.
         *
         * This isn't the ideal place to do this, because if the devices loses power while
         * sitting on the "game over" screen we won't record the score.  In practice the
         * user will either leave the game or the device will go to sleep, pausing the activity,
         * so it's not a real concern.
         *
         * We could improve on this by having GameState manage the high score, but since
         * we're using Preferences to hold it, we'd need to pass the Activity through.  This
         * interferes with the idea of keeping GameState isolated from the application UI.
         *
         * Note that doing this update in the Preferences code in BreakoutActivity is a
         * bad idea, because that would prevent us from recording a high score until the user
         * hit "back" to return to the initial Activity -- which won't happen if they just
         * hit the "home" button to quit.
         *
         * BreakoutActivity will need to see the updated high score.  The Android lifecycle
         * is defined such that our onPause() will execute before BreakoutActivity's onResume()
         * is called (see "Coordinating Activities" in the developer guide page for Activities),
         * so they'll be able to pick up whatever we do here.
         *
         * We need to do this *after* the call to mGLView.onPause(), because that causes
         * GameState to save the game to static storage, and that's what we read the score from.
         */updateHighScore(GameState.getFinalScore())
    }

    override fun onResume() {
        /*
         * Complement of onPause().  We're required to call the GLView's onResume().
         *
         * We don't restore the saved game state here, because we want to wait until after the
         * objects have been created (since much of the game state is held within the objects).
         * In any event we need it to run on the Renderer thread, so we let the restore happen
         * in GameSurfaceRenderer's onSurfaceCreated() method.
         */
        Log.d(TAG, "GameActivity resuming")
        super.onResume()
        mGLView?.onResume()
    }

    /**
     * Configures the GameState object with the configuration options set by BreakoutActivity.
     */
    private fun configureGameState() {
        val maxLives: Int
        val minSpeed: Int
        val maxSpeed: Int
        val ballSize: Float
        val paddleSize: Float
        val scoreMultiplier: Float
        // todo use arrays refactor this
        when (sDifficultyIndex) {
            0 -> {
                ballSize = 2.0f
                paddleSize = 2.0f
                scoreMultiplier = 0.75f
                maxLives = 4
                minSpeed = 200
                maxSpeed = 500
            }
            1 -> {
                ballSize = 1f
                paddleSize = 1.0f
                scoreMultiplier = 1.0f
                maxLives = 3
                minSpeed = 300
                maxSpeed = 800
            }
            2 -> {
                ballSize = 1.0f
                paddleSize = 0.8f
                scoreMultiplier = 1.25f
                maxLives = 3
                minSpeed = 600
                maxSpeed = 1200
            }
            3 -> {
                ballSize = 1.0f
                paddleSize = 0.5f
                scoreMultiplier = 0.1f
                maxLives = 1
                minSpeed = 1000
                maxSpeed = 100000
            }
            else -> throw RuntimeException("bad difficulty index $sDifficultyIndex")
        }
        mGameState?.setBallSizeMultiplier(ballSize)
        mGameState?.setPaddleSizeMultiplier(paddleSize)
        mGameState?.setScoreMultiplier(scoreMultiplier)
        mGameState?.setMaxLives(maxLives)
        mGameState?.setBallInitialSpeed(minSpeed)
        mGameState?.setBallMaximumSpeed(maxSpeed)
        mGameState?.setNeverLoseBall(sNeverLoseBall)
        SoundResources.setSoundEffectsEnabled(sSoundEffectsEnabled)
    }



    /**
     * Updates high score.  If the new score is higher than the previous score, the entry
     * is updated.
     *
     * @param lastScore Score from the last completed game.
     */
    private fun updateHighScore(lastScore: Int) {
        val prefs = getSharedPreferences(BreakoutActivity.PREFS_NAME, MODE_PRIVATE)
        val highScore = prefs.getInt(BreakoutActivity.HIGH_SCORE_KEY, 0)
        Log.d(TAG, "final score was $lastScore")
        if (lastScore > highScore) {
            Log.d(TAG, "new high score!  ($highScore vs. $lastScore)")
            val editor = prefs.edit()
            editor.putInt(BreakoutActivity.HIGH_SCORE_KEY, lastScore)
            editor.commit()
        }
    }


    companion object {

        private const val TAG = "GameActivity"

        private const val DIFFICULTY_MIN = 0
        private const val DIFFICULTY_MAX = 3 // inclusive

        private const val DIFFICULTY_DEFAULT = 1
        private var sDifficultyIndex = 0


        private var sNeverLoseBall = false

        private var sSoundEffectsEnabled = false

        /**
         * Gets the difficulty index, used to configure the game parameters.
         */
        fun getDifficultyIndex(): Int {
            return sDifficultyIndex
        }

        /**
         * Gets the default difficulty index.  This should be used if no preference has been saved.
         */
        fun getDefaultDifficultyIndex(): Int {
            return DIFFICULTY_DEFAULT
        }

        /**
         * Configures various tunable parameters based on the difficulty index.
         *
         *
         * Changing the value will cause a game in progress to reset.
         */
        fun setDifficultyIndex(difficultyIndex: Int) {
            // This could be coming from preferences set by a different version of the game.  We
            // want to be tolerant of values we don't recognize.
            var difficultyIndex = difficultyIndex
            if (difficultyIndex < DIFFICULTY_MIN || difficultyIndex > DIFFICULTY_MAX) {
                Log.w(TAG, "Invalid difficulty index $difficultyIndex, using default")
                difficultyIndex = DIFFICULTY_DEFAULT
            }
            if (sDifficultyIndex !== difficultyIndex) {
                sDifficultyIndex = difficultyIndex
                invalidateSavedGame()
            }
        }

        /**
         * Gets the "never lose a ball" option.
         */
        fun getNeverLoseBall(): Boolean {
            return sNeverLoseBall
        }

        /**
         * Configures the "never lose a ball" option.  If set, the ball bounces off the bottom
         * (incurring a point deduction) instead of draining out.
         *
         *
         * Changing the value will cause a game in progress to reset.
         */
        fun setNeverLoseBall(neverLoseBall: Boolean) {
            if (sNeverLoseBall !== neverLoseBall) {
                sNeverLoseBall = neverLoseBall
                invalidateSavedGame()
            }
        }

        /**
         * Gets sound effect status.
         */
        fun getSoundEffectsEnabled(): Boolean {
            return sSoundEffectsEnabled
        }

        /**
         * Enables or disables sound effects.
         *
         *
         * Changing the value does not affect a game in progress.
         */
        fun setSoundEffectsEnabled(soundEffectsEnabled: Boolean) {
            sSoundEffectsEnabled = soundEffectsEnabled
        }

        /**
         * Invalidates the current saved game.
         */
        fun invalidateSavedGame() {
            GameState.invalidateSavedGame()
        }

        /**
         * Determines whether our saved game is for a game in progress.
         */
        fun canResumeFromSave(): Boolean {
            return GameState.canResumeFromSave()
        }

    }
}
