package com.frank.breakout.activity


import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.frank.breakout.opengl.view.AboutBox
import com.frank.breakout.R
import com.frank.breakout.databinding.MainBinding
import hugo.weaving.DebugLog


/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
class BreakoutActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener {

    private lateinit var binding: MainBinding

    // Highest score seen so far.
    private var mHighScore = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Populate difficulty-level spinner.
        val spinner = binding.layMain2.spinnerDifficultyLevel

        // Need to create one of these fancy ArrayAdapter thingies, and specify the generic layout
        // for the widget itself.
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.difficulty_level_names, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // Apply the adapter to the spinner.
        spinner.adapter = adapter
        spinner.onItemSelectedListener = this
    }

    @DebugLog
    override fun onPause() {
        super.onPause()
        savePreferences()
    }

    @DebugLog
    override fun onResume() {
        super.onResume()
        restorePreferences()
        updateControls()
    }


    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val difficulty = (parent as Spinner).selectedItemPosition

        GameActivity.setDifficultyIndex(difficulty)
        updateControls() // dim the "resume" button if value changed

    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        TODO("Not yet implemented")
    }

    /**
     * Sets the state of the UI controls to match our internal state.
     */
    private fun updateControls() {
        val difficulty = binding.layMain2.spinnerDifficultyLevel
        difficulty.setSelection(GameActivity.getDifficultyIndex())
        val resume: Button = binding.layMain1.buttonResumeGame
        resume.isEnabled = GameActivity.canResumeFromSave()
        val neverLoseBall = binding.layMain2.checkboxNeverLoseBall
        neverLoseBall.isChecked = GameActivity.getNeverLoseBall()
        val soundEffectsEnabled = binding.layMain2.checkboxSoundEffectsEnabled
        soundEffectsEnabled.isChecked = GameActivity.getSoundEffectsEnabled()
        val highScore = binding.layMain1.textHighScore
        highScore.text = mHighScore.toString()
    }

    /**
     * onClick handler for "new game" button.
     */
    fun clickNewGame(view: View?) {
        GameActivity.invalidateSavedGame()
        startGame()
    }

    /**
     * onClick handler for "resume game" button.
     */
    fun clickResumeGame(view: View?) {
        startGame()
    }

    /**
     * Fires an Intent that starts the GameActivity.
     */
    private fun startGame() {
        /*
         * We want to start or resume the game, passing our configuration options along.  When
         * control returns to this Activity, we want to know if the game is still in progress
         * (so we can enable the "resume" button), and what the score is (so we can update the
         * high-score table).
         *
         * Passing the configuration options through an Intent seems natural.  However, there
         * are a few sticking points:
         *
         *  (1) If an earlier game is in progress, and we'd like to kill it and start a new one,
         *      we want to pass a "restart" flag through.  If we just drop a boolean Extra in,
         *      the game will restart every time we rotate the screen, because the full Intent
         *      is re-sent.  We can remove the Extra with Intent.removeExtra() after we first
         *      receive it, or we can pass a "game serial number" through, and have GameActivity
         *      only do the reset if the serial number doesn't match the previous value.
         *  (2) We need to know if the game is resumable so we can configure the "resume"
         *      button.  We could get this by starting the Activity with startActivityForResult
         *      and providing an onActivityResult handler.  The renderer could set the result to
         *      "playing", and then change it to "game over" when the game stops animating.
         *      The result's current value would be returned when GameActivity exited.
         *  (3) We need the high score.  We could bit-mask this into the integer Activity
         *      result, but that feels like a misuse of the API.  We could create an Intent
         *      Extra for the score and provide that along with the result.  The more annoying
         *      issue here is that you can't call setResult in GameActivity's onPause, because
         *      it's too late in the lifecycle -- the result needs to be set before then.  We
         *      either need to insert ourselves into the Activity teardown sequence and set the
         *      result earlier, or maybe update the Activity result value every time the score
         *      changes.  The latter is bad for us because setResult isn't guaranteed to not
         *      cause allocations.
         *
         * There are other tricks we could use -- maybe use a specific Intent to query the
         * current state of the game and have the Activity call setResult and return immediately --
         * but none of them really fit the intended purpose of the API calls.  (If you're
         * working this hard to make the APIs do something, chances are you're misusing them.)
         *
         * Instead, we just store the state statically in GameActivity, and launch the game with
         * a trivial Intent.
         */
        val intent = Intent(this, GameActivity::class.java)
        startActivity(intent)
    }

    /**
     * onClick handler for "about" button.
     */
    fun clickAbout(view: View?) {
        AboutBox.display(this)
    }


    /**
     * onClick handler for "never lose ball".
     */
    fun clickNeverLoseBall(view: View) {
        /*
         * This method only gets called if the state changes, and any state change invalidates
         * a game in progress.  Call updateControls() to dim the "resume" button.
         *
         * We could combine handlers with the other checkbox and switch on view.getId() to see
         * which one was hit.  For our needs, having separate methods is cleaner.
         */
        GameActivity.setNeverLoseBall((view as CheckBox).isChecked)
        updateControls() // dim the "resume" button
    }

    /**
     * onClick handler for "sound effects enabled".
     */
    fun clickSoundEffectsEnabled(view: View) {
        /*
         * The call to updateControls() isn't really necessary, because changing this value
         * doesn't invalidate the saved game.  In general though it's up to GameActivity to
         * decide what does and doesn't spoil a game, and it's possible the behavior could
         * change in the future, so we call it to be safe.
         */
        GameActivity.setSoundEffectsEnabled((view as CheckBox).isChecked)
        updateControls()
    }

    /**
     * Copies settings to the saved preferences.
     */
    private fun savePreferences() {
        /*
         * We could put a version number in the preferences so that, if a future version of the
         * app substantially changes the meaning of the preferences, we have a way to figure
         * out what they mean (or figure out that we can't understand them).  We only have a
         * handful of preferences, and the only interesting one -- the difficulty index -- is
         * trivial to range-check.  We don't need it, so we're not going to build it.  (And
         * if we need it later, the absence of a version number in the prefs is telling, so
         * we're not going to end up in a situation where we can't decipher the prefs file.)
         */
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(DIFFICULTY_KEY, GameActivity.getDifficultyIndex())
        editor.putBoolean(NEVER_LOSE_BALL_KEY, GameActivity.getNeverLoseBall())
        editor.putBoolean(SOUND_EFFECTS_ENABLED_KEY, GameActivity.getSoundEffectsEnabled())
        editor.commit()
    }

    /**
     * Retrieves settings from the saved preferences.  Also picks up the high score.
     */
    private fun restorePreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // If the saved prefs come from a different version of the game, the difficulty level
        // might be out of range.  The code in GameActivity will reset it to default.
        GameActivity.setDifficultyIndex(
            prefs.getInt(
                DIFFICULTY_KEY,
                GameActivity.getDefaultDifficultyIndex()
            )
        )
        GameActivity.setNeverLoseBall(prefs.getBoolean(NEVER_LOSE_BALL_KEY, false))
        GameActivity.setSoundEffectsEnabled(prefs.getBoolean(SOUND_EFFECTS_ENABLED_KEY, true))
        mHighScore = prefs.getInt(HIGH_SCORE_KEY, 0)
    }

    companion object {
        /**
         * Whether or not the system UI should be auto-hidden after
         * [AUTO_HIDE_DELAY_MILLIS] milliseconds.
         */
        private const val AUTO_HIDE = true

        /**
         * If [AUTO_HIDE] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = 3000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300

        const val TAG = "breakout"

        /**
         * Put up the game menu, and show the high score.
         *
         * We allow the user to resume a game in progress, mostly as a way to ensure that if they
         * accidentally hit the "back" button (or hit it on purpose to pause the game) they can pick
         * up where they left off.  We don't want to deal with game parameters being adjusted
         * mid-game, so we want to disable the "resume" button if the user changes an important
         * setting.
         *
         * This means we need to set listeners on our controls, watch for state changes, and
         * update the UI.  The mere fact of receiving a callback on certain controls isn't
         * significant -- our Spinner callback will execute when the Activity is created -- so we
         * need to track the actual changes.
         *
         * The various configuration options are stored in a preferences file.  This makes them
         * permanent across app restarts as well as Activity pause/resume.
         */

        // Shared preferences file.
        const val PREFS_NAME = "PrefsAndScores"

        // Keys for values saved in our preferences file.
        private const val DIFFICULTY_KEY = "difficulty"
        private const val NEVER_LOSE_BALL_KEY = "never-lose-ball"
        private const val SOUND_EFFECTS_ENABLED_KEY = "sound-effects-enabled"
        const val HIGH_SCORE_KEY = "high-score"
    }

}