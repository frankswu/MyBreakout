package com.frank.breakout.controller

import android.graphics.Rect
import android.util.Log
import com.frank.breakout.activity.BreakoutActivity
import com.frank.breakout.opengl.Ball
import com.frank.breakout.opengl.Brick
import com.frank.breakout.opengl.OutlineAlignedRect
import com.frank.breakout.opengl.base.*
import com.frank.breakout.opengl.view.GameSurfaceRenderer

class GameState {


    // Gameplay configurables.  These may not be changed while the game is in progress, and
    // changing a value invalidates the saved game.
    private var mNeverLoseBall = false // if true, bounce off the bottom

    private var mMaxLives = 3
    private var mBallInitialSpeed = 300
    private var mBallMaximumSpeed = 800
    private var mBallSizeMultiplier = 1.0f
    private var mPaddleSizeMultiplier = 1.0f
    private var mScoreMultiplier = 1.0f

    private val mBorders: Array<BasicAlignedRect?> = arrayOfNulls(NUM_BORDERS)
    private var mBackground: BasicAlignedRect? = null

    /*
     * Our brick collection, in a single linear array.  To stay in the theme of OpenGL, this is
     * in column-major order, i.e. the first N blocks on the left side are the first N members
     * of the array.
     */
    private val mBricks: Array<Brick?> = arrayOfNulls(BRICK_COLUMNS * BRICK_ROWS)
    private var mLiveBrickCount = 0

    private val mScoreDigits: Array<TexturedAlignedRect?> =
        arrayOfNulls<TexturedAlignedRect>(NUM_SCORE_DIGITS)

    /*
     * Text resources, notably including an image texture for our various text strings.
     */
    private var mTextRes: TextResources? = null

    private var mGamePlayState = 0

    private var mIsAnimating = false
    private var mLivesRemaining = 0
    private var mScore = 0

    private var mGameStatusMessages: TexturedAlignedRect? = null
    private var mGameStatusMessageNum = 0
    private var mDebugFramedString = 0

    private val mPossibleCollisions: Array<BaseRect?> =
        arrayOfNulls(BRICK_COLUMNS * BRICK_ROWS + NUM_BORDERS + NUM_SCORE_DIGITS + 1 /*paddle*/)
    private var mHitDistanceTraveled // result from findFirstCollision()
            = 0f
    private var mHitXAdj = 0f
    private var mHitYAdj: Float = 0f // result from findFirstCollision()

    //:kotlin.Float = 0f
    private var mHitFace // result from findFirstCollision()
            = 0
    private var mDebugCollisionRect // visual debugging
            : OutlineAlignedRect? = null

    private var mBall: Ball? = null
    private var mPrevFrameWhenNsec: Long = 0

    /*
     * Pause briefly on certain transitions, e.g. before launching a new ball after one was lost.
     */
    private var mPauseDuration = 0f

    /*
     * Debug feature: do the next N frames in slow motion.  Useful when examining collisions.
     * The speed will ramp up to normal over the last 60 frames.  (This is a debug feature, not
     * part of the game, so we just count frames and assume the panel is somewhere near 60fps.)
     * See DEBUG_COLLISIONS for example usage.
     */
    private var mDebugSlowMotionFrames = 0
    var mRecentTimeDelta = DoubleArray(RECENT_TIME_DELTA_COUNT)
    var mRecentTimeDeltaNext = 0
    private var mPaddle: BasicAlignedRect? = null


    companion object {
        /**
         * Marks the saved game as invalid.
         *
         *
         * May be called from a non-Renderer thread.
         */
        fun invalidateSavedGame() {
            synchronized(sSavedGame) { sSavedGame.mIsValid = false }
        }

        /**
         * Determines whether we have saved a game that can be resumed.  We would need to have a valid
         * saved game and be playing or about to play.
         *
         *
         * May be called from a non-Renderer thread.
         */
        fun canResumeFromSave(): Boolean {
            synchronized(sSavedGame) {
                //Log.d(TAG, "canResume: valid=" + sSavedGame.mIsValid
                //        + " state=" + sSavedGame.mGamePlayState);
                return sSavedGame.mIsValid &&
                        (sSavedGame.mGamePlayState === GAME_PLAYING ||
                                sSavedGame.mGamePlayState === GAME_READY)
            }
        }

        /**
         * Gets the score from a completed game.
         *
         *
         * If we returned the score of a game in progress, we could get excessively high results for
         * games where points may be deducted (e.g. never-lose-ball mode).
         *
         *
         * May be called from a non-Renderer thread.
         *
         * @return The score, or -1 if the current save state doesn't hold a completed game.
         */
        fun getFinalScore(): Int {
            synchronized(sSavedGame) {
                return if (sSavedGame.mIsValid &&
                    (sSavedGame.mGamePlayState === GAME_WON ||
                            sSavedGame.mGamePlayState === GAME_LOST)
                ) {
                    sSavedGame.mScore
                } else {
                    //Log.d(TAG, "No score: valid=" + sSavedGame.mIsValid
                    //        + " state=" + sSavedGame.mGamePlayState);
                    -1
                }
            }
        }

        private const val TAG = BreakoutActivity.TAG
        const val DEBUG_COLLISIONS = false // enable increased logging
        const val SHOW_DEBUG_STUFF = false // enable on-screen debugging

        // In-memory saved game.  The game is saved and restored whenever the Activity is paused
        // and resumed.  This should be the only static variable in GameState.
        private val sSavedGame: SavedGame = SavedGame()


        /*
     * Size of the "arena".  We pretend we have a fixed-size screen with this many pixels in it.
     * Everything gets scaled to the viewport before display, so this is just an artificial
     * construct that allows us to work with integer values.  It also allows us to save and
     * restore values in a screen-dimension-independent way, which would be useful if a saved
     * game were moved between devices through The Cloud.
     *
     * The values here are completely arbitrary.  I find it easier to read debug output with
     * 3-digit integer values than, say, floating point numbers between 0.0 and 1.0.  What
     * really matters is the proportion of width to height, since that defines the shape of
     * the play area.
     */
        const val ARENA_WIDTH = 768.0f
        const val ARENA_HEIGHT = 1024.0f

        /*
     * The arena looks something like this (remember, GL coordinates start in lower-left corner):
     *
     * +-----------------------------+
     * |          (empty)      score |  <- 90%
     * |                             |  <- 80%
     * | brick brick brick brick ... |  <- 70%
     * | brick brick brick brick ... |  <- 60%
     * | brick brick brick brick ... |  <- 50%
     * | brick brick brick brick ... |  <- 40%
     * |                             |  <- 30%
     * |          (empty)            |  <- 20%
     * |          !paddle!           |  <- 10%
     * |          (empty)            |  <-  0%
     * +-----------------------------+
     *
     * We scale bricks so they fill the middle area, split into 8 rows and 16 columns.
     *
     * The arena has a (width * 2%) border on three sides (bottom is open).  The border is
     * inside the arena, not an external decoration.
     *
     * The size of the ball is flexible, except that we require it to be round (looks nicer
     * than a square).  Knowing the shape is important for collision detection, which must
     * treat the ball as a circle rather than a rectangle.
     *
     * The paddle is another simple rect, and the size can change based on the current
     * difficulty level.
     *
     * Positions and sizes are specified in percentages, because it's easier to get a sense for
     * how things are laid out when reading the constants than it would with absolute coordinate
     * values.  It also means things will adjust automatically if we decide to change the
     * proportions of the arena.
     */
        private const val BRICK_TOP_PERC = 85 / 100.0f
        private const val BRICK_BOTTOM_PERC = 43 / 100.0f
        private const val BORDER_WIDTH_PERC = 2 / 100.0f
        private const val BRICK_COLUMNS = 12
        private const val BRICK_ROWS = 8

        private const val BORDER_WIDTH: Float = (BORDER_WIDTH_PERC * ARENA_WIDTH)

        /*
     * The top / right position of the score digits.  The digits are part of the arena, drawn
     * "under" the ball, and we want them to be as far up and to the right as possible without
     * interfering with the border.
     *
     * The text size is specified in terms of the height of a single digit.  That is, we scale
     * the font texture proportionally so the height matches the target.  The idea is to
     * have N fixed-width "cells" for the digits, where N is determined by the highest possible
     * score.
     */
        private const val SCORE_TOP = ARENA_HEIGHT - BORDER_WIDTH * 2
        private const val SCORE_RIGHT = ARENA_WIDTH - BORDER_WIDTH * 2
        private const val SCORE_HEIGHT_PERC = 5 / 100.0f

        /*
     * We compute the size of each "brick zone" based on the amount of space available.  A
     * brick zone is a single brick plus the blank area around it.  The size of the border gap is
     * determined by these two constants.  If we use 5% on each side, we get a 10% gap between
     * bricks (except at the outer edges).
     *
     * If the gap between bricks is large enough, the ball can "tunnel" between rows or
     * columns if it hits at the right angle.
     *
     * Set these to zero to have a solid block of bricks.
     */
        private const val BRICK_HORIZONTAL_GAP_PERC = 20 / 100.0f
        private const val BRICK_VERTICAL_GAP_PERC = 50 / 100.0f

        /*
     * Vertical position for the paddle, and paddle dimensions.  The height (i.e. thickness) is
     * a % of the arena height, and the width is a unit size, based on % of arena width.  The
     * width can be increased or decreased based on skill level.
     *
     * We want the paddle to be a little higher up on the screen than it would be in a
     * mouse-based game because there needs to be enough room for the player's finger under the
     * paddle.  Depending on the screen dimensions and orientation there may or may not be some
     * touch space outside the viewport, but we can't rely on that.
     */
        private const val PADDLE_VERTICAL_PERC = 12 / 100.0f
        private const val PADDLE_HEIGHT_PERC = 1 / 100.0f
        private const val PADDLE_WIDTH_PERC = 2 / 100.0f
        private const val PADDLE_DEFAULT_WIDTH = 6

        /*
     * Ball dimensions.  Internally it's just a rect, but we'll give it a circular texture so
     * it looks round.  Size is a percentage of the arena width.  This can also be adjusted
     * for skill level, up to a fairly goofy level.
     */
        private const val BALL_WIDTH_PERC = 2.5f / 100.0f

        /*
     * Rects used for drawing the border and background.  We want the background to be a solid
     * not-quite-black color, with easily visible borders that the ball will bounce off of.  We
     * have a few options:
     *
     *  - We can draw a full-screen rect in the border color, then an inset rect in the
     *    background color.  This does a rather massive amount of overdraw and isn't going
     *    to work well on fill-rate-limited devices.
     *  - We can glClear to the border color and then draw the background inset.  Better
     *    performance, but it has an unwanted side-effect: glClear sets the color in the entire
     *    framebuffer, not just the viewport area.  We want the area outside the game arena to
     *    be black.
     *  - We can draw the arena background and borders separately.  We will touch each pixel
     *    only once (not including the glClear).
     *
     * The last option gives us the best performance for the visual appearance we want.  Also,
     * by defining the border rects as individual entities, we have something to hand to the
     * collision detection code, so we can use the general rect collision algorithm instead of
     * having separate "did I hit a border" tests.
     *
     * Border 0 is special -- it's the bottom of the screen, and colliding with it means you
     * lose the ball.  A more general solution would be to create a "Border" class and define
     * any special characteristics there, but that's overkill for this game.
     */
        private const val NUM_BORDERS = 4
        private const val BOTTOM_BORDER = 0

        /*
     * The ball.  The diameter is configurable, either for different skill levels or for
     * amusement value.
     */
        private const val DEFAULT_BALL_DIAMETER = (ARENA_WIDTH * BALL_WIDTH_PERC).toInt()

        /*
     * Timestamp of previous frame.  Used for animation.  We cap the maximum inter-frame delta
     * at 0.5 seconds, so that a major hiccup won't cause things to behave too crazily.
     */
        private const val NANOS_PER_SECOND = 1000000000.0
        private const val MAX_FRAME_DELTA_SEC = 0.5
        private const val FRAME_RATE_SMOOTHING = false
        private const val RECENT_TIME_DELTA_COUNT = 5

        /*
     * Storage for collision detection results.
     */
        private const val HIT_FACE_NONE = 0
        private const val HIT_FACE_VERTICAL = 1
        private const val HIT_FACE_HORIZONTAL = 2
        private const val HIT_FACE_SHARPCORNER = 3

        /*
     * Game play state.
     */
        private const val GAME_INITIALIZING = 0
        private const val GAME_READY = 1
        private const val GAME_PLAYING = 2
        private const val GAME_WON = 3
        private const val GAME_LOST = 4

        /*
     * Events that can happen when the ball moves.
     */
        private const val EVENT_NONE = 0
        private const val EVENT_LAST_BRICK = 1
        private const val EVENT_BALL_LOST = 2

        /*
     * Text message to display in the middle of the screen (e.g. "won" or "game over").
     */
        private const val STATUS_MESSAGE_WIDTH_PERC = 85 / 100.0f

        /*
     * Score display.
     *
     * The maximum score for a 12x8 grid of bricks is 43200.  In "hard" mode the score is
     * multiplied by 1.25.  floor(log10(43200*1.25))+1 is 5.
     *
     * If the number of bricks or score values isn't fixed at compile time, we will need to
     * compute this at runtime, and allocate the score rects in the constructor.  It is fixed,
     * though, so we can be lazy and just hard-code a value here.
     */
        private const val NUM_SCORE_DIGITS = 5

        /*
     * The paddle.  The width of the paddle is configurable based on skill level.
     */
        private const val DEFAULT_PADDLE_WIDTH =
            (ARENA_WIDTH * PADDLE_WIDTH_PERC * PADDLE_DEFAULT_WIDTH).toInt()
    }


    /*
     * Trivial setters for configurables.  Changing any of these values will invalidate the
     * current saved game.  If a game is being played when the value changes, unpredictable
     * behavior may result.
     *
     * We can check to see if the value has changed, and invalidate the save if so.  We can
     * throw an exception if a game is in progress.  Or we can be lazy and assume that the
     * higher-level code in GameActivity is managing this correctly.  (Currently doing the latter.)
     *
     * These are called from a non-Renderer thread, before the Renderer thread starts.
     */
    fun setNeverLoseBall(neverLoseBall: Boolean) {
        mNeverLoseBall = neverLoseBall
    }

    fun setMaxLives(maxLives: Int) {
        mMaxLives = maxLives
    }

    fun setBallInitialSpeed(speed: Int) {
        mBallInitialSpeed = speed
    }

    fun setBallMaximumSpeed(speed: Int) {
        mBallMaximumSpeed = speed
    }

    fun setBallSizeMultiplier(mult: Float) {
        mBallSizeMultiplier = mult
    }

    fun setPaddleSizeMultiplier(mult: Float) {
        mPaddleSizeMultiplier = mult
    }

    fun setScoreMultiplier(mult: Float) {
        mScoreMultiplier = mult
    }

    /**
     * Resets game state to initial values.  Does not reallocate any storage or access saved
     * game state.
     */
    private fun reset() {
        /*
        * This is called when we're asked to restore a game, but no saved game exists.  The
        * various objects (e.g. bricks) have already been initialized.  If a saved game
        * does exist, we'll never call here, so don't treat this like a constructor.
        */
        mGamePlayState = GAME_INITIALIZING
        mIsAnimating = true
        mGameStatusMessageNum = TextResources.NO_MESSAGE
        mPrevFrameWhenNsec = 0
        mPauseDuration = 0.0f
        mRecentTimeDeltaNext = -1
        mLivesRemaining = mMaxLives
        mScore = 0
        resetBall()
        //mLiveBrickCount = 0;      // initialized by allocBricks
    }

    /**
     * Moves the ball to its start position, resetting direction and speed to initial values.
     */
    private fun resetBall() {
        mBall?.setDirection(-0.3f, -1.0f)
        mBall?.speed = mBallInitialSpeed
        mBall?.setPosition(ARENA_WIDTH / 2.0f + 45, ARENA_HEIGHT * BRICK_BOTTOM_PERC - 100)
    }

    /**
     * Saves game state into static storage.
     */
    fun save() {
        /*
         * Our game state is distributed across many objects, e.g. each brick object knows
         * whether or not it is alive.  We want to copy the interesting bits into an easily
         * serializable object, so that we can preserve game state across app restarts.
         *
         * This is overkill for a silly breakout game -- we could just declare everything in
         * GameState "static" and it would work just as well (unless we wanted to preserve
         * state when the app process is killed by the system).  It's a useful exercise though,
         * and by avoiding statics we allow the GC to discard all the game state when the
         * GameActivity goes away.
         *
         * We synchronize on the object because multiple threads can access it.
         */
        synchronized(sSavedGame) {
            val save = sSavedGame
            val bricks = BooleanArray(BRICK_ROWS * BRICK_COLUMNS)
            for (i in bricks.indices) {
                bricks[i] = mBricks[i]!!.isAlive
            }
            save.mLiveBricks = bricks
            save.mBallXDirection = mBall!!.xDirection
            save.mBallYDirection = mBall!!.yDirection
            save.mBallXPosition = mBall!!.xPosition
            save.mBallYPosition = mBall!!.getYPosition()
            save.mBallSpeed = mBall!!.speed
            save.mPaddlePosition = mPaddle!!.xPosition
            save.mGamePlayState = mGamePlayState
            save.mGameStatusMessageNum = mGameStatusMessageNum
            save.mLivesRemaining = mLivesRemaining
            save.mScore = mScore
            save.mIsValid = true
        }

        //Log.d(TAG, "game saved");
    }

    /**
     * Restores game state from save area.  If no saved game is available, we just reset
     * the values.
     *
     * @return true if we restored from a saved game.
     */
    fun restore(): Boolean {
        synchronized(sSavedGame) {
            val save = sSavedGame
            if (!save.mIsValid) {
                Log.d(TAG, "No valid saved game found")
                reset()
                save() // initialize save area
                return false
            }
            val bricks = save.mLiveBricks
            for (i in bricks.indices) {
                if (bricks[i]) {
                    // board creation sets all bricks to "live", don't need to setAlive() here
                } else {
                    mBricks[i]?.isAlive = false
                    mLiveBrickCount--
                }
            }
            //Log.d(TAG, "live brickcount is " + mLiveBrickCount);
            mBall?.setDirection(save.mBallXDirection, save.mBallYDirection)
            mBall?.setPosition(save.mBallXPosition, save.mBallYPosition)
            mBall?.speed = save.mBallSpeed
            movePaddle(save.mPaddlePosition)
            mGamePlayState = save.mGamePlayState
            mGameStatusMessageNum = save.mGameStatusMessageNum
            mLivesRemaining = save.mLivesRemaining
            mScore = save.mScore
        }

        //Log.d(TAG, "game restored");
        return true
    }

    /**
     * Performs some housekeeping after the Renderer surface has changed.
     *
     *
     * This is called after a screen rotation or when returning to the app from the home screen.
     */
    fun surfaceChanged() {
        // Pause briefly.  This gives the user time to orient themselves after a screen
        // rotation or switching back from another app.
        setPauseTime(1.5f)

        // Reset this so we don't leap forward.  (Not strictly necessary because of the
        // game pause we set above -- we don't advance the ball state on the first frames we
        // draw, so this will reset naturally.)
        mPrevFrameWhenNsec = 0

        // We need to draw the screen at least once, so set this whether or not we're actually
        // animating.  If we're in a "game over" state, this will go back to "false" right away.
        mIsAnimating = true
    }

    /**
     * Sets the TextResources object that the game will use.
     */
    fun setTextResources(textRes: TextResources) {
        mTextRes = textRes
    }


    /**
     * Returns true if we want the system to call our draw methods.
     */
    fun isAnimating(): Boolean {
        return mIsAnimating
    }

    /**
     * Allocates the bricks, setting their sizes and positions.  Sets mLiveBrickCount.
     */
    fun allocBricks() {
        val totalBrickWidth = ARENA_WIDTH - BORDER_WIDTH * 2
        val brickWidth = totalBrickWidth / BRICK_COLUMNS
        val totalBrickHeight = ARENA_HEIGHT * (BRICK_TOP_PERC - BRICK_BOTTOM_PERC)
        val brickHeight = totalBrickHeight / BRICK_ROWS
        val zoneBottom = ARENA_HEIGHT * BRICK_BOTTOM_PERC
        val zoneLeft = BORDER_WIDTH
        for (i in 0 until mBricks.size) {
            val brick = Brick()
            val row = i / BRICK_COLUMNS
            val col = i % BRICK_COLUMNS
            val bottom = zoneBottom + row * brickHeight
            val left = zoneLeft + col * brickWidth

            // Brick position specifies the center point, so need to offset from bottom left.
            brick.setPosition(left + brickWidth / 2, bottom + brickHeight / 2)

            // Brick size is the size of the "brick zone", scaled down by a few % on each edge.
            brick.setScale(
                brickWidth * (1.0f - BRICK_HORIZONTAL_GAP_PERC),
                brickHeight * (1.0f - BRICK_VERTICAL_GAP_PERC)
            )

            // Assign a position-dependent color.  A smooth gradient looks nice when there are
            // gaps, but if the gaps are zero you really want more of a checkerboard pattern.
            val factor: Float =
                i.toFloat() / (mBricks.size - 1) // [0..1], linear across all bricks
            val oddness = row and 1 xor (col and 1) // 0 or 1, every other brick
            brick.setColor(factor, 1.0f - factor, 0.25f + 0.20f * oddness)

            // Score is based on row, with lower bricks being worth less than the top bricks.
            // The point value here is for a game at normal difficulty.  We multiply by 100
            // because that makes everything MORE EXCITING!!!
            brick.scoreValue = (row + 1) * 100
            brick.isAlive = true
            mBricks[i] = brick
        }

        //Log.d(TAG, "Brick zw=" + brickWidth + " zh=" + brickHeight
        //        + " w=" + mBricks[0].getXScale() + " h=" + mBricks[0].getYScale()
        //        + " gapw=" + (brickWidth - mBricks[0].getXScale())
        //        + " gaph=" + (brickHeight - mBricks[0].getYScale()));
        if (false) {
            // The maximum possible score determines how many digits we need to display.
            var max = 0
            for (j in 0 until BRICK_ROWS) {
                max += (j + 1) * BRICK_COLUMNS
            }
            Log.d(TAG, "max score on 'normal' is " + max * 100)
        }
        mLiveBrickCount = mBricks.size
    }

    /**
     * Draws the "live" bricks.
     */
    fun drawBricks(): GameState {
        mBricks.let {
            it.forEach { o -> if (o?.isAlive == true) o.draw() }
        }
        return this
    }

    /**
     * Allocates the rects that define the borders and background.
     */
    fun allocBorders() {

        // Need one rect that covers the entire play area (i.e. viewport) in the background color.
        // (We could tighten this up a bit so we don't get overdrawn by the borders, but that's
        // a minor concern.)
        var rect = BasicAlignedRect()
        rect.setPosition(ARENA_WIDTH / 2, ARENA_HEIGHT / 2)
        rect.setScale(ARENA_WIDTH, ARENA_HEIGHT)
        rect.setColor(0.1f, 0.1f, 0.1f)
        mBackground = rect

        // This rect is just off the bottom of the arena.  If we collide with it, the ball is
        // lost.  This must be BOTTOM_BORDER (zero).
        rect = BasicAlignedRect()
        rect.setPosition(ARENA_WIDTH / 2, -BORDER_WIDTH / 2)
        rect.setScale(ARENA_WIDTH, BORDER_WIDTH)
        rect.setColor(1.0f, 0.65f, 0.0f)
        mBorders[BOTTOM_BORDER] = rect

        // Need one rect each for left / right / top.
        rect = BasicAlignedRect()
        rect.setPosition(BORDER_WIDTH / 2, ARENA_HEIGHT / 2)
        rect.setScale(BORDER_WIDTH, ARENA_HEIGHT)
        rect.setColor(0.6f, 0.6f, 0.6f)
        mBorders[1] = rect
        rect = BasicAlignedRect()
        rect.setPosition(ARENA_WIDTH - BORDER_WIDTH / 2, ARENA_HEIGHT / 2)
        rect.setScale(BORDER_WIDTH, ARENA_HEIGHT)
        rect.setColor(0.6f, 0.6f, 0.6f)
        mBorders[2] = rect
        rect = BasicAlignedRect()
        rect.setPosition(ARENA_WIDTH / 2, ARENA_HEIGHT - BORDER_WIDTH / 2)
        rect.setScale(ARENA_WIDTH - BORDER_WIDTH * 2, BORDER_WIDTH)
        rect.setColor(0.6f, 0.6f, 0.6f)
        mBorders[3] = rect
    }

    /**
     * Draws the border and background rects.
     */
    fun drawBorders(): GameState {
        mBackground?.draw()
        mBorders.let {
            it.forEach { o -> o?.draw() }
        }
        return this
    }

    /**
     * Creates the paddle.
     */
    fun allocPaddle() {
        val rect = BasicAlignedRect()
        rect.setScale(
            DEFAULT_PADDLE_WIDTH * mPaddleSizeMultiplier,
            ARENA_HEIGHT * PADDLE_HEIGHT_PERC
        )
        rect.setColor(1.0f, 1.0f, 1.0f) // note color is cycled during pauses
        rect.setPosition(ARENA_WIDTH / 2.0f, ARENA_HEIGHT * PADDLE_VERTICAL_PERC)
        Log.d(TAG, "paddle y=${rect.getYPosition()}")
        mPaddle = rect
    }

    /**
     * Draws the paddle.
     */
    fun drawPaddle(): GameState {
        mPaddle?.draw()
        return this
    }

    /**
     * Moves the paddle to a new location.  The requested position is expressed in arena
     * coordinates, but does not need to be clamped to the viewable region.
     *
     *
     * The final position may be slightly different due to collisions with walls or
     * side-contact with the ball.
     */
    fun movePaddle(arenaX: Float) {
        /*
         * If we allow the paddle to be moved inside the ball (e.g. a quick sideways motion at a
         * time when the ball is on the same horizontal line), the collision detection code may
         * react badly.  This can happen if we move the paddle without regard for the position
         * of the ball.
         *
         * The problem is easy to demonstrate with a ball that has a large radius and a slow
         * speed.  If the paddle deeply intersects the ball, you either have to ignore the
         * collision and let the ball pass through the paddle (which looks weird), or bounce off.
         * When bouncing off we have to adjust the ball position so it no longer intersects with
         * the paddle, which means a large jarring jump in position, or ignoring additional
         * collisions, since they could cause the ball to reverse direction repeatedly
         * (essentially just vibrating in place).
         *
         * We can handle this by running the paddle movement through the same collision
         * detection code that the ball uses, and stopping it when we collide with something
         * (the ball or walls).  That would work well if the paddle were smoothly sliding, but
         * our control scheme allows absolute jumps -- the paddle instantly goes wherever you
         * touch on the screen.  If the paddle were on the far right, and you touched the far
         * left, you'd expect it to go to the far left even if the ball was "in the way" in
         * the middle of the screen.  (This is mitigated if you arrange it so that the paddle
         * appears to knock the ball sideways when it collides -- then it's apparent to the user
         * that the paddle was stopped by a collision with the ball.)
         *
         * The visual artifacts of making the ball leap are minor given the speed of animation
         * and the size of objects on screen, so I'm currently just ignoring the problem.  The
         * moral of the story is that everything that moves needs to tested for collisions
         * with all objects.
         */
        var arenaX = arenaX
        val paddleWidth: Float = mPaddle?.getXScale()?.div(2) ?: 0f
        val minX = BORDER_WIDTH + paddleWidth
        val maxX = ARENA_WIDTH - BORDER_WIDTH - paddleWidth
        if (arenaX < minX) {
            arenaX = minX
        } else if (arenaX > maxX) {
            arenaX = maxX
        }
        mPaddle?.xPosition = (arenaX)
    }

    /**
     * Creates the ball.
     */
    fun allocBall() {
        val ball = Ball()
        val diameter = (DEFAULT_BALL_DIAMETER * mBallSizeMultiplier)
        // ovals don't work right -- collision detection requires a circle
        ball.setScale(diameter, diameter)
        mBall = ball
    }

    /**
     * Draws the "live" ball and the remaining-lives display.
     */
    fun drawBall() : GameState {
        /*
         * We use the lone mBall object to draw all instances of the ball.  We just move it
         * around for each instance.
         */
        val ball: Ball = mBall!!
        val savedX: Float = ball.xPosition
        val savedY: Float = ball.getYPosition()
        val radius: Float = ball.radius
        var xpos = BORDER_WIDTH * 2 + radius
        val ypos = BORDER_WIDTH + radius
        var lives = mLivesRemaining
        val ballIsLive = mGamePlayState !== GAME_INITIALIZING && mGamePlayState !== GAME_READY
        if (ballIsLive) {
            // In READY state we show the "live" ball next to the "remaining" balls, rather than
            // in the play area.
            lives--
        }
        for (i in 0 until lives) {
            // Vibrate the "remaining lives" balls when we're almost out of bricks.  It's
            // kind of silly, but it's easy to do.
            var jitterX = 0.0f
            var jitterY = 0.0f
            if (mLiveBrickCount > 0 && mLiveBrickCount < 4) {
                jitterX = ((4 - mLiveBrickCount) * (Math.random() - 0.5) * 2).toFloat()
                jitterY = ((4 - mLiveBrickCount) * (Math.random() - 0.5) * 2).toFloat()
            }
            ball.setPosition(xpos + jitterX, ypos + jitterY)
            ball.draw()
            xpos += radius * 3
        }
        ball.setPosition(savedX, savedY)
        if (ballIsLive) {
            ball.draw()
        }
        return this
    }

    /**
     * Creates objects required to display a numeric score.
     */
    fun allocScore() {
        /*
         * The score digits occupy a fixed position at the top right of the screen.  They're
         * actually part of the arena, and sit "under" the ball.  (We could, in fact, have the
         * ball collide with them.)
         *
         * We want to use fixed-size cells for the digits.  Each digit has a different width
         * though (which is somewhat true even if we use a monospace font -- a '1' can measure
         * narrower than an '8' because the text metrics ignore the padding).  We want to run
         * through and figure out what the widest glyph is, and use that as the cell width.
         *
         * The basic plan is to find the widest glyph, scale it up to match the height we
         * want, and use that as the size of a cell.  The digits are drawn scaled up to that
         * height, with the width increased proportionally (a given digit may not fill the
         * entire width of the cell).
         */
        var maxWidth = 0
        var widest: Rect? = null
        for (i in 0..9) {
            val boundsRect: Rect = mTextRes!!.getTextureRect(TextResources.DIGIT_START + i)!!
            val rectWidth: Int = boundsRect.width()
            if (maxWidth < rectWidth) {
                maxWidth = rectWidth
                widest = boundsRect
            }
        }
        val widthHeightRatio: Float? = (widest?.width()?.div(widest.height()))?.toFloat()
        val cellHeight = ARENA_HEIGHT * SCORE_HEIGHT_PERC
        val cellWidth = cellHeight * widthHeightRatio!! * 1.05f // add 5% spacing between digits

        // Note these are laid out from right to left, i.e. mScoreDigits[0] is the 1s digit.
        val top = SCORE_TOP
        val right = SCORE_RIGHT
        for (i in 0 until NUM_SCORE_DIGITS) {
            mScoreDigits[i] = TexturedAlignedRect()
            mScoreDigits[i]?.setTexture(
                mTextRes!!.textureHandle,
                mTextRes!!.textureWidth, mTextRes!!.textureHeight
            )
            mScoreDigits[i]?.setPosition(
                SCORE_RIGHT - i * cellWidth - cellWidth / 2,
                SCORE_TOP - cellHeight / 2
            )
        }
    }

    /**
     * Draws the current score.
     */
    fun drawScore(): GameState {
        val cellHeight = ARENA_HEIGHT * SCORE_HEIGHT_PERC
        var score = mScore
        for (i in 0 until NUM_SCORE_DIGITS) {
            val value = score % 10
            val boundsRect: Rect = mTextRes!!.getTextureRect(TextResources.DIGIT_START + value)!!
            val ratio: Float = cellHeight / boundsRect.height()
            val scoreCell: TexturedAlignedRect = mScoreDigits[i]!!
            scoreCell.setTextureCoords(boundsRect)
            scoreCell.setScale(boundsRect.width() * ratio, cellHeight)
            scoreCell.draw()
            score /= 10
        }
        return this
    }

    /**
     * Creates storage for a message to display in the middle of the screen.
     */
    fun allocMessages() {
        /*
         * The messages (e.g. "won" and "lost") are stored in the same texture, so the choice
         * of which text to show is determined by the texture coordinates stored in the
         * TexturedAlignedRect.  We can update those without causing an allocation, so there's
         * no need to allocate a separate drawable rect for every possible message.
         */
        mGameStatusMessages = TexturedAlignedRect()
        mGameStatusMessages?.setTexture(
            mTextRes!!.textureHandle,
            mTextRes!!.textureWidth, mTextRes!!.textureHeight
        )
        mGameStatusMessages?.setPosition(ARENA_WIDTH / 2, ARENA_HEIGHT / 2)
    }

    /**
     * If appropriate, draw a message in the middle of the screen.
     */
    fun drawMessages() : GameState {
        if (mGameStatusMessageNum !== TextResources.NO_MESSAGE) {
            val msgBox: TexturedAlignedRect = mGameStatusMessages!!
            val boundsRect: Rect = mTextRes!!.getTextureRect(mGameStatusMessageNum)!!
            msgBox.setTextureCoords(boundsRect)

            /*
             * We need to scale the text to be easily readable.  We have a basic choice to
             * make: do we want the message text to always be the same size (e.g. always at
             * 50 points), or should it be as large as it can be on the screen?
             *
             * For the mid-screen message, which is one or two words, we want it to be as large
             * as it can get.  The expected strings will be much wider than they are tall, so
             * we scale the width of the bounding box to be a fixed percentage of the arena
             * width.  This means the glyphs in "hello" will be much larger than they would be
             * in "hello, world", but that's exactly what we want.
             *
             * If we wanted consistent-size text, we'd need to change the way the TextResource
             * code works.  It doesn't attempt to preserve the font metrics, and the bounding
             * boxes are based on the heights of the glyphs used in a given string (i.e. not
             * all possible glyphs in the font) so we just don't have enough information in
             * here to do that.
             */
            val scale: Float = ARENA_WIDTH * STATUS_MESSAGE_WIDTH_PERC / boundsRect.width()
            msgBox.setScale(boundsRect.width() * scale, boundsRect.height() * scale)

            //Log.d(TAG, "drawing " + mGameStatusMessageNum);
            msgBox.draw()
        }
        return this
    }

    /**
     * Allocates shapes that we use for "visual debugging".
     */
    fun allocDebugStuff() {
        mDebugCollisionRect = OutlineAlignedRect()
        mDebugCollisionRect?.setColor(1.0f, 0.0f, 0.0f)
    }

    /**
     * Renders debug features.
     *
     *
     * This function is allowed to violate the "don't allocate objects" rule.
     */
    fun drawDebugStuff() {
        if (!SHOW_DEBUG_STUFF) {
            return
        }

        // Draw a red outline rectangle around the ball.  This shows the area that was
        // examined for collisions during the "coarse" pass.
        if (true) {
            OutlineAlignedRect.prepareToDraw()
            mDebugCollisionRect?.draw()
            OutlineAlignedRect.finishedDrawing()
        }

        // Draw the entire message texture so we can see what it looks like.
        if (true) {
            val textureWidth: Int = mTextRes!!.textureWidth
            val textureHeight: Int = mTextRes!!.textureHeight
            val scale = ARENA_WIDTH * STATUS_MESSAGE_WIDTH_PERC / textureWidth

            // Draw an orange rect around the texture.
            val outline = OutlineAlignedRect()
            outline.setPosition(ARENA_WIDTH / 2, ARENA_HEIGHT / 2)
            outline.setScale(textureWidth * scale + 2, textureHeight * scale + 2)
            outline.setColor(1.0f, 0.65f, 0.0f)
            OutlineAlignedRect.prepareToDraw()
            outline.draw()
            OutlineAlignedRect.finishedDrawing()

            // Draw the full texture.  Note you can set the background to opaque white in
            // TextResources to see what the drop shadow looks like.
            var boundsRect = Rect(0, 0, textureWidth, textureHeight)
            val msgBox: TexturedAlignedRect = mGameStatusMessages!!
            msgBox.setTextureCoords(boundsRect)
            msgBox.setScale(textureWidth * scale, textureHeight * scale)
            TexturedAlignedRect.drawContent {
                msgBox.draw()
            }


            // Draw a rectangle around each individual text item.  We draw a different one each
            // time to get a flicker effect, so it doesn't fully obscure the text.
            if (true) {
                outline.setColor(1.0f, 1.0f, 1.0f)
                val stringNum = mDebugFramedString
                mDebugFramedString = (mDebugFramedString + 1) % TextResources.numStrings
                boundsRect = mTextRes!!.getTextureRect(stringNum)!!
                // The bounds rect is in bitmap coordinates, with (0,0) in the top left.  Translate
                // it to an offset from the center of the bitmap, and find the center of the rect.
                var boundsCenterX: Float = boundsRect.exactCenterX() - textureWidth / 2
                var boundsCenterY: Float = boundsRect.exactCenterY() - textureHeight / 2
                // Now scale it to arena coordinates, using the same scale factor we used to
                // draw the texture with all the messages, and translate it to the center of
                // the arena.  We need to invert Y to match GL conventions.
                boundsCenterX = ARENA_WIDTH / 2 + boundsCenterX * scale
                boundsCenterY = ARENA_HEIGHT / 2 - boundsCenterY * scale
                // Set the values and draw the rect.
                outline.setPosition(boundsCenterX, boundsCenterY)
                outline.setScale(boundsRect.width() * scale, boundsRect.height() * scale)
                OutlineAlignedRect.prepareToDraw()
                outline.draw()
                OutlineAlignedRect.finishedDrawing()
            }
        }
    }

    /**
     * Sets the pause time.  The game will continue to execute and render, but won't advance
     * game state.  Used at the start of the game to give the user a chance to orient
     * themselves to the board.
     *
     *
     * May also be handy during debugging to see stuff (like the ball at the instant of a
     * collision) without fully stopping the game.
     */
    private fun setPauseTime(durationMsec: Float) {
        mPauseDuration = durationMsec
    }

    /**
     * Updates all game state for the next frame.  This primarily consists of moving the ball
     * and checking for collisions.
     */
    fun calculateNextFrame() {
        // First frame has no time delta, so make it a no-op.
        if (mPrevFrameWhenNsec == 0L) {
            mPrevFrameWhenNsec = System.nanoTime() // use monotonic clock
            mRecentTimeDeltaNext = -1 // reset saved values
            return
        }

        /*
         * The distance the ball must travel is determined by the time between frames and the
         * current speed (expressed in arena-units per second).  What we actually want to know
         * is how much time will elapse between the *display* of the previous frame and the
         * *display* of the current frame, but this is close enough.
         *
         * If onDrawFrame() is being called immediately after vsync, we should get a pretty
         * steady pace (e.g. a device with 60fps refresh will call the method every 16.7ms).
         * If we're getting called on some other schedule the span for each frame could vary
         * by quite a bit.  Also note that not all devices operate at 60fps.
         *
         * Smoothing frames by averaging the last few deltas can reduce noticeable jumps,
         * but create the possibility that you won't be animating at exactly the right
         * speed.  For our purposes it doesn't seem to matter.
         *
         * It's interesting to note that, because "deltaSec" varies, and our collision handling
         * isn't perfectly precise, the game is not deterministic.  Variations in frame rate
         * lead to minor variations in the ball's path.  If you want reproducible behavior
         * for debugging, override deltaSec with a fixed value (e.g. 1/60).
         */
        val nowNsec = System.nanoTime()
        var curDeltaSec = (nowNsec - mPrevFrameWhenNsec) / NANOS_PER_SECOND
        if (curDeltaSec > MAX_FRAME_DELTA_SEC) {
            // We went to sleep for an extended period.  Cap it at a reasonable limit.
            Log.d(TAG, "delta time was $curDeltaSec, capping at $MAX_FRAME_DELTA_SEC")
            curDeltaSec = MAX_FRAME_DELTA_SEC
        }
        var deltaSec: Double
        if (FRAME_RATE_SMOOTHING) {
            if (mRecentTimeDeltaNext < 0) {
                // first time through, fill table with current value
                for (i in 0 until RECENT_TIME_DELTA_COUNT) {
                    mRecentTimeDelta[i] = curDeltaSec
                }
                mRecentTimeDeltaNext = 0
            }
            mRecentTimeDelta[mRecentTimeDeltaNext] = curDeltaSec
            mRecentTimeDeltaNext = (mRecentTimeDeltaNext + 1) % RECENT_TIME_DELTA_COUNT
            deltaSec = 0.0
            for (i in 0 until RECENT_TIME_DELTA_COUNT) {
                deltaSec += mRecentTimeDelta[i]
            }
            deltaSec /= RECENT_TIME_DELTA_COUNT
        } else {
            deltaSec = curDeltaSec
        }
        var advanceFrame = true

        // If we're in a pause, animate the color of the paddle, but don't advance any state.
        if (mPauseDuration > 0.0f) {
            advanceFrame = false
            if (mPauseDuration > deltaSec) {
                mPauseDuration -= deltaSec.toFloat()
                if (mGamePlayState === GAME_PLAYING) {
                    // rotate through yellow, magenta, cyan
                    val colors: FloatArray = mPaddle!!.color
                    when {
                        colors[0] == 0.0f -> {
                            mPaddle?.setColor(1.0f, 0.0f, 1.0f)
                        }
                        colors[1] == 0.0f -> {
                            mPaddle?.setColor(1.0f, 1.0f, 0.0f)
                        }
                        else -> {
                            mPaddle?.setColor(0.0f, 1.0f, 1.0f)
                        }
                    }
                }
            } else {
                // leaving pause, restore paddle color to white
                mPauseDuration = 0.0f
                mPaddle?.setColor(1.0f, 1.0f, 1.0f)
            }
        }
        when (mGamePlayState) {
            GAME_INITIALIZING -> mGamePlayState = GAME_READY
            GAME_READY -> {
                mGameStatusMessageNum = TextResources.READY
                if (advanceFrame) {
                    // "ready" has expired, move ball to starting position
                    mGamePlayState = GAME_PLAYING
                    mGameStatusMessageNum = TextResources.NO_MESSAGE
                    setPauseTime(0.5f)
                    advanceFrame = false
                }
            }
            GAME_WON -> {
                mGameStatusMessageNum = TextResources.WINNER
                mIsAnimating = false
                advanceFrame = false
            }
            GAME_LOST -> {
                mGameStatusMessageNum = TextResources.GAME_OVER
                mIsAnimating = false
                advanceFrame = false
            }
            GAME_PLAYING -> {
            }
            else -> Log.e(TAG, "GLITCH: bad state $mGamePlayState")
        }

        // If we're playing, move the ball around.
        if (advanceFrame) {
            val event = moveBall(deltaSec)
            when (event) {
                EVENT_LAST_BRICK -> {
                    mGamePlayState = GAME_WON
                    // We're already playing the brick sound; play the other three sounds
                    // simultaneously.  Cheap substitute for an actual "victory" sound.
                    SoundResources.play(SoundResources.PADDLE_HIT)
                    SoundResources.play(SoundResources.WALL_HIT)
                    SoundResources.play(SoundResources.BALL_LOST)
                }
                EVENT_BALL_LOST -> if (--mLivesRemaining == 0) {
                    // game over, man
                    mGamePlayState = GAME_LOST
                } else {
                    // switch back to "ready" state, reset ball position
                    mGamePlayState = GAME_READY
                    mGameStatusMessageNum = TextResources.READY
                    setPauseTime(1.5f)
                    resetBall()
                }
                EVENT_NONE -> {
                }
                else -> throw RuntimeException("bad game event: $event")
            }
        }
        mPrevFrameWhenNsec = nowNsec
    }

    /**
     * Moves the ball, checking for and reporting collisions as we go.
     *
     * @return A value indicating special events (won game, lost ball).
     */
    private fun moveBall(deltaSec: Double): Int {
        /*
         * Movement and collision detection is done with two checks, "coarse" and "fine".
         *
         * First, we take the current position of the ball, and compute where it will be
         * for the next frame.  We compute a box that encloses both the current and next
         * positions (an "axis-aligned bounding box", or AABB).  For every object in the list,
         * including the borders and paddle, we do quick test for a collision.  If nothing
         * matches, we just jump the ball forward.
         *
         * If we do get some matches, we need to do a finer-grained test to see if (a) we
         * actually hit something, and (b) how far along the ball's path we were when we
         * first collided.
         *
         * If we did hit something, we need to update the ball's motion vector based on which
         * edge or corner we hit, and restart the whole process from the point of the collision.
         * The ball is now moving in a different direction, so the "coarse" information we
         * gathered previously is no longer valid.
         *
         * There can be multiple collisions in a single frame, and we need to catch them all.
         *
         * (Given an insanely fast-moving ball, or a ball with a really large radius, or various
         * other crazy parameters, it's possible to hit every brick in a single frame.)
         */
        var event = EVENT_NONE
        val radius: Float = mBall!!.radius
        var distance: Float = (mBall?.speed?.times(deltaSec))?.toFloat()!!
        //Log.d(TAG, "delta=" + deltaSec * 60.0f + " dist=" + distance);
        if (mDebugSlowMotionFrames > 0) {
            // Simulate a "slow motion" mode by reducing distance.  The reduction is constant
            // until the last 60 frames, which ramps the speed up gradually.
            val SLOW_FACTOR = 8.0f
            val RAMP_FRAMES = 60.0f
            val div: Float = if (mDebugSlowMotionFrames > RAMP_FRAMES) {
                SLOW_FACTOR
            } else {
                // At frame 60, we want the full slowdown.  At frame 0, we want no slowdown.
                // STEP is how much we want to subtract from SLOW_FACTOR at each step.
                val STEP = (SLOW_FACTOR - 1.0f) / RAMP_FRAMES
                SLOW_FACTOR - STEP * (RAMP_FRAMES - mDebugSlowMotionFrames)
            }
            distance /= div
            mDebugSlowMotionFrames--
        }
        while (distance > 0.0f) {
            val curX: Float = mBall!!.xPosition
            val curY: Float = mBall!!.getYPosition()
            val dirX: Float = mBall!!.xDirection
            val dirY: Float = mBall!!.yDirection
            val finalX = curX + dirX * distance
            val finalY = curY + dirY * distance
            var left: Float
            var right: Float
            var top: Float
            var bottom: Float

            /*
             * Find the edges of the rectangle described by the ball's start and end position.
             * The (x,y) values identify the center, so factor in the radius too.
             *
             * Per GL conventions, values get larger moving toward the top-right corner.
             */if (curX < finalX) {
                left = curX - radius
                right = finalX + radius
            } else {
                left = finalX - radius
                right = curX + radius
            }
            if (curY < finalY) {
                bottom = curY - radius
                top = finalY + radius
            } else {
                bottom = finalY - radius
                top = curY + radius
            }
            /* debug */
            mDebugCollisionRect?.setPosition((curX + finalX) / 2, (curY + finalY) / 2)
            mDebugCollisionRect?.setScale(right - left, top - bottom)
            var hits = 0

            // test bricks
            for (i in 0 until mBricks.size) {
                if (mBricks[i]?.isAlive == true &&
                    checkCoarseCollision(mBricks[i]!!, left, right, bottom, top)
                ) {
                    mPossibleCollisions[hits++] = mBricks[i]
                }
            }

            // test borders
            for (i in 0 until NUM_BORDERS) {
                if (checkCoarseCollision(mBorders[i]!!, left, right, bottom, top)) {
                    mPossibleCollisions[hits++] = mBorders[i]
                }
            }

            // test paddle
            if (checkCoarseCollision(mPaddle!!, left, right, bottom, top)) {
                mPossibleCollisions[hits++] = mPaddle
            }

            // test score... because we can
            if (false) {
                for (i in 0 until NUM_SCORE_DIGITS) {
                    // It's possible to get the ball wedged up behind the score digits if they're
                    // too far from the wall relative to the size of the ball.  (I haven't seen it
                    // actually get stuck, but it's a possibility.)  To do this right, we need a
                    // collision rect that covers the digits and extends all the way to the
                    // borders, or some random jitter in the collision vector that ensures we
                    // can't enter a stable state.
                    if (checkCoarseCollision(mScoreDigits[i]!!, left, right, bottom, top)) {
                        mPossibleCollisions[hits++] = mScoreDigits[i]
                    }
                }
            }
            if (hits != 0) {
                // may have hit something, look closer
                val hit: BaseRect? = findFirstCollision(
                    mPossibleCollisions, hits, curX, curY,
                    dirX, dirY, distance, radius
                )
                if (hit == null) {
                    // didn't actually hit, clear counter
                    hits = 0
                } else {
                    if (GameSurfaceRenderer.EXTRA_CHECK) {
                        if (mHitDistanceTraveled <= 0.0f) {
                            Log.e(TAG, "GLITCH: collision detection didn't move the ball")
                            mHitDistanceTraveled = distance
                        }
                    }

                    // Update posn for the actual distance traveled and the collision adjustment
                    val newPosX = curX + dirX * mHitDistanceTraveled + mHitXAdj
                    val newPosY: Float = curY + dirY * mHitDistanceTraveled + mHitYAdj
                    mBall?.setPosition(newPosX, newPosY)
                    if (DEBUG_COLLISIONS) {
                        Log.d(TAG, "COL: intermediate cx=$newPosX cy=$newPosY")
                    }

                    // Update the direction vector based on the nature of the surface we
                    // struck.  We will override this for collisions with the paddle.
                    var newDirX = dirX
                    var newDirY = dirY
                    when (mHitFace) {
                        HIT_FACE_HORIZONTAL -> newDirY = -dirY
                        HIT_FACE_VERTICAL -> newDirX = -dirX
                        HIT_FACE_SHARPCORNER -> {
                            newDirX = -dirX
                            newDirY = -dirY
                        }
                        HIT_FACE_NONE -> Log.e(TAG, "GLITCH: unexpected hit face$mHitFace")
                        else -> Log.e(TAG, "GLITCH: unexpected hit face$mHitFace")
                    }


                    /*
                     * Figure out what we hit, and react.  A conceptually cleaner way to do
                     * this would be to define a "collision" action on every BaseRect object,
                     * and call that.  This is very straightforward for the object state update
                     * handling (e.g. remove brick, make sound), but gets a little more
                     * complicated for collisions that don't follow the basic rules (e.g. hitting
                     * the paddle) or special events (like hitting the very last brick).  We're
                     * not trying to build a game engine, so we just use a big if-then-else.
                     *
                     * Playing a sound here may not be the best approach.  If the sound code
                     * takes a while to queue up sounds, we could stall the game/render thread
                     * and reduce our frame rate.  It might be better to queue up sounds on a
                     * separate thread.  However, unless the ball is moving at an absurd speed,
                     * we shouldn't be colliding with more than two objects in a single frame,
                     * so we shouldn't be stressing SoundPool much.
                     */if (hit is Brick) {
                        val brick: Brick? = hit
                        brick?.isAlive = false
                        mLiveBrickCount--
                        mScore += brick?.scoreValue?.times(mScoreMultiplier.toInt()) ?: 0
                        if (mLiveBrickCount == 0) {
                            Log.d(TAG, "*** won ***")
                            event = EVENT_LAST_BRICK
                            distance = 0.0f
                        }
                        SoundResources.play(SoundResources.BRICK_HIT)
                    } else if (hit === mPaddle) {
                        if (mHitFace === HIT_FACE_HORIZONTAL) {
                            val paddleWidth: Float = mPaddle!!.getXScale()
                            val paddleLeft: Float = mPaddle!!.xPosition - paddleWidth / 2
                            var hitAdjust = (newPosX - paddleLeft) / paddleWidth

                            // Adjust the ball's motion based on where it hit the paddle.
                            //
                            // hitPosn ranges from 0.0 to 1.0, with a little bit of overlap
                            // because the ball is round (it's based on the ball's *center*,
                            // not the actual point of impact on the paddle itself -- something
                            // we could correct by getting additional data out of the collision
                            // detection code, but we can just as easily clamp it).
                            //
                            // The location determines how we alter the X velocity.  We want
                            // this to be more pronounced at the edges of the paddle, especially
                            // if the ball is hitting the "outside edge".
                            //
                            // Direction is a vector, normalized by the "set direction" method.
                            // We don't need to worry about dirX growing without bound.
                            //
                            // This bit of code has a substantial impact on the "feel" of
                            // the game.  It could probably use more tweaking.
                            if (hitAdjust < 0.0f) {
                                hitAdjust = 0.0f
                            }
                            if (hitAdjust > 1.0f) {
                                hitAdjust = 1.0f
                            }
                            val hitPercent = (hitAdjust * 100.0f).toInt()
                            hitAdjust -= 0.5f
                            if (Math.abs(hitAdjust) > 0.25) {   // outer 25% on each side
                                hitAdjust *= if (dirX < 0 && hitAdjust > 0 || dirX > 0 && hitAdjust < 0) {
                                    //Log.d(TAG, "outside corner, big jump");
                                    1.6f
                                } else {
                                    //Log.d(TAG, "far corner, modest jump");
                                    1.2f
                                }
                            }
                            hitAdjust *= 1.25f
                            //Log.d(TAG, " hitPerc=" + hitPercent + " hitAdj=" + hitAdjust
                            //        + " old dir=" + dirX + "," + dirY);
                            newDirX += hitAdjust
                            var maxRatio = 3.0f
                            if (Math.abs(newDirX) > Math.abs(newDirY) * maxRatio) {
                                // Limit the angle so we don't get too crazily horizontal.  Note
                                // the ball could be moving downward after a collision if we're
                                // in "never lose" mode and we bounced off the bottom of the
                                // paddle, so we can't assume newDirY is positive.
                                //Log.d(TAG, "capping Y vel to " + maxRatio + ":1");
                                if (newDirY < 0) {
                                    maxRatio = -maxRatio
                                }
                                newDirY = Math.abs(newDirX) / maxRatio
                            }
                        }
                        SoundResources.play(SoundResources.PADDLE_HIT)
                    } else if (hit === mBorders[BOTTOM_BORDER]) {
                        // We hit the bottom border.  It might be a little weird visually to
                        // bounce off of it when the ball is lost, so if we hit it we stop the
                        // current frame of computation immediately.  (Moving the border farther
                        // off screen doesn't work -- too far and there's a long delay waiting
                        // for a slow ball to drain, too close and we still get the bounce effect
                        // from a fast-moving ball.)
                        if (!mNeverLoseBall) {
                            event = EVENT_BALL_LOST
                            distance = 0.0f
                            SoundResources.play(SoundResources.BALL_LOST)
                        } else {
                            mScore -= (500 * mScoreMultiplier).toInt()
                            if (mScore < 0) {
                                mScore = 0
                            }
                            SoundResources.play(SoundResources.WALL_HIT)
                        }
                    } else {
                        // hit a border or a score digit
                        SoundResources.play(SoundResources.WALL_HIT)
                    }

                    // Increase speed by 3% after each (super-elastic!) collision, capping
                    // at the skill-level-dependent maximum speed.
                    var speed: Int = mBall!!.speed
                    speed += (mBallMaximumSpeed - mBallInitialSpeed) * 3 / 100
                    if (speed > mBallMaximumSpeed) {
                        speed = mBallMaximumSpeed
                    }
                    mBall?.speed = speed
                    mBall?.setDirection(newDirX, newDirY)
                    distance -= mHitDistanceTraveled
                    if (DEBUG_COLLISIONS) {
                        Log.d(
                            TAG, "COL: remaining dist=" + distance + " new dirX=" +
                                    mBall?.xDirection + " dirY=" + mBall?.yDirection
                        )
                    }
                }
            }
            if (hits == 0) {
                // hit nothing, move ball to final position and bail
                if (DEBUG_COLLISIONS) {
                    Log.d(TAG, "COL: none (dist was $distance)")
                }
                mBall?.setPosition(finalX, finalY)
                distance = 0.0f
            }
        }
        return event
    }

    /**
     * Determines whether the target object could possibly collide with a ball whose current
     * and future position are enclosed by the l/r/b/t values.
     *
     * @return true if we might collide with this object.
     */
    private fun checkCoarseCollision(
        target: BaseRect, left: Float, right: Float,
        bottom: Float, top: Float
    ): Boolean {
        /*
         * This is a "coarse" detection, so we can play fast and loose.  One approach is to
         * essentially draw a circle around each object, and see if the circles intersect.
         * This requires a simple distance test -- if the distance between the center points
         * of the objects is greater than their combined radii, there's no chance of collision.
         * Mathematically, each test is two multiplications and a compare.
         *
         * This is a very sloppy test for a fast-moving ball, though, because we're drawing
         * it around the current and final position.  If the ball is moving quickly from left
         * to right, we will end up testing for collisions in a large area above and below
         * the ball, because the circle extends in all directions.
         *
         * A better test, given the generally rectangular nature of all of our objects, would
         * be to test the draw rects for overlap.  This is precise for all objects except the
         * ball itself, and even for that it has a better-confined region.  Each test requires
         * a handful of additions and comparisons, and on a device with an FPU will be slower.
         *
         * If we're really concerned about performance, we can skip brick collision detection
         * entirely at the top and bottom of the board with a simple range check.  The brick
         * area can then be divided into a grid with 64 cells, and each brick can hold a long
         * integer that has bits set based on what cells it is a part of.  We set up a bit
         * vector with the set of cells that the ball could touch as it moves between the old
         * and new positions, and do a quick bit mask to check for collisions.
         *
         * And so on.
         *
         * At the end of the day we've got about a hundred bricks, the four edges of the screen,
         * and the paddle.  We just want to do something simple that will cut the number of
         * objects we need to check in the "fine" pass to a handful.
         */

        // Convert position+scale into l/r/b/t.
        val xpos: Float
        val ypos: Float
        val xscale: Float
        val yscale: Float
        val targLeft: Float
        val targRight: Float
        val targBottom: Float
        val targTop: Float
        xpos = target.xPosition
        ypos = target.getYPosition()
        xscale = target.getXScale()
        yscale = target.getYScale()
        targLeft = xpos - xscale
        targRight = xpos + xscale
        targBottom = ypos - yscale
        targTop = ypos + yscale

        // If the smallest right is bigger than the biggest left, and the smallest bottom is
        // bigger than the biggest top, we overlap.
        //
        // FWIW, this is essentially an application of the Separating Axis Theorem for two
        // axis-aligned rects.
        val checkLeft = if (targLeft > left) targLeft else left
        val checkRight = if (targRight < right) targRight else right
        val checkTop = if (targBottom > bottom) targBottom else bottom
        val checkBottom = if (targTop < top) targTop else top
        return checkRight > checkLeft && checkBottom > checkTop
    }

    /**
     * Tests for a collision with the rectangles in mPossibleCollisions as the ball travels from
     * (curX,curY).
     *
     *
     * We can't return multiple values from a method call in Java.  We don't want to allocate
     * storage for the return value on each frame (this being part of the main game loop).  We
     * can define a class that holds all of the return values and allocate a single instance
     * of it when GameState is constructed, or just drop the values into dedicated return-value
     * fields.  The latter is incrementally easier, so we return the object we hit, and store
     * additional details in these fields:
     *
     *  * mHitDistanceLeft - the amount of distance remaining to travel after impact
     *  * mHitFace - what face orientation we hit
     *  * mHitXAdj, mHitYAdj - position adjustment so objects won't intersect
     *
     *
     * @param rects Array of rects to test against.
     * @param numRects Number of rects in array.
     * @param curX Current X position.
     * @param curY Current Y position.
     * @param dirX X component of normalized direction vector.
     * @param dirY Y component of normalized direction vector.
     * @param distance Distance to travel.
     * @param radius Radius of the ball.
     * @return The object we struck, or null if none.
     */
    private fun findFirstCollision(
        rects: Array<BaseRect?>, numRects: Int, curX: Float,
        curY: Float, dirX: Float, dirY: Float, distance: Float,
        radius: Float
    ): BaseRect? {
        /*
         * The "coarse" function has indicated that a collision is possible.  We need to get
         * an exact determination of what we're hitting.
         *
         * We can either use some math to compute the time of intersection of each rect with
         * the moving ball (a "sweeping" collision test, perhaps even straying into
         * "continuous collision detection"), or we can just step the ball forward until
         * it collides with something or reaches the end point.  The latter isn't as precise,
         * but is much simpler, so we'll do that.
         *
         * We can use a test similar to the Separating Axis Theorem, but with a circle vs.
         * rectangle collision it's possible for the axis-aligned projections to overlap but
         * not have a collision (e.g. the circle is near one corner).  We need to perform an
         * additional test to check the distance from the closest vertex to the center of the
         * circle.  The fancy way to figure out which corner is closest is with Voronoi regions,
         * but we don't really need that: since we're colliding with axis-aligned rects, we can
         * just collapse the whole thing into a single quadrant.
         *
         * Nice illustration here:
         *  http://stackoverflow.com/questions/401847/circle-rectangle-collision-detection-intersection
         *
         * Once we determine that a collision has occurred, we need to determine where we hit
         * so that we can decide how to bounce.  For our bricks we're either hitting a vertical
         * or horizontal surface; these will cause us to invert the X component or Y component
         * of our direction vector.  It also makes sense visually to reverse direction when
         * you run into a corner.
         *
         * It's possible to get "tunneling" effects, which may look weird but are actually
         * legitimate.  Two common scenarios:
         *
         *  (1) Suppose the ball is moving upward and slightly to the left.  If it
         *      squeezes between the gap in the bricks and hits a right edge, it will
         *      do a vertical-surface bounce (i.e. start moving back to the right), and
         *      almost immediately hit the vertical surface of the brick to the right.
         *      With the right angle, this can repeat in a nearby column and climb up through
         *      several layers.  (Unless the ball is small relative to the gap between bricks,
         *      this is hard to do in practice.)
         *  (2) A "sharp corner" bounce can keep the ball moving upward.  For
         *      example, a ball moving up and right hits the bottom of a brick,
         *      and heads down and to the right.  It hits the top-left corner of
         *      a brick, and reverses direction (up and left).  It hits the bottom
         *      of another brick, and while moving down and left it hits the
         *      top-right corner of a fourth brick.  If the angle is right, this
         *      pattern will continue, knocking out a vertical tunnel.  Because it's
         *      hitting on corners, this is easy to do even if the horizontal gap
         *      between bricks is fairly narrow.
         *
         * The smaller the inter-brick gap is, the less likely the tunneling
         * effects are to occur.  With a small enough gap (and a reasonable MAX_STEP)
         * it's impossible to hit an "inside" corner or surface.
         *
         * It's possible to collide with two shapes at once.  We ignore this situation.
         * Whichever object we happen to examine first gets credit.
         */

        // Maximum distance, in arena coordinates, we advance the ball on each iteration of
        // the loop.  If this is too small, we'll do a lot of unnecessary iterations.  If it's
        // too large (e.g. more than the ball's radius), the ball can end up inside an object,
        // or pass through one entirely.
        val MAX_STEP = 2.0f

        // Minimum distance.  After a collision the objects are just barely in contact, so at
        // each step we need to move a little or we'll double-collide.  The minimum exists to
        // ensure that we don't get hosed by floating point round-off error.
        val MIN_STEP = 0.001f
        val radiusSq = radius * radius
        var faceHit = HIT_FACE_NONE
        var faceToAdjust = HIT_FACE_NONE
        val xadj = 0.0f
        val yadj = 0.0f
        var traveled = 0.0f
        while (traveled < distance) {
            // Travel a bit.
            if (distance - traveled > MAX_STEP) {
                traveled += MAX_STEP
            } else if (distance - traveled < MIN_STEP) {
                //Log.d(TAG, "WOW: skipping tiny step distance " + (distance - traveled));
                break
            } else {
                traveled = distance
            }
            val circleXWorld = curX + dirX * traveled
            val circleYWorld = curY + dirY * traveled
            for (i in 0..numRects) {
                kotlin.run {
                    rects[i]?.let {
                        val rect: BaseRect = it
                        val rectXWorld: Float = rect.xPosition
                        val rectYWorld: Float = rect.getYPosition()
                        val rectXScaleHalf: Float = rect.getXScale() / 2.0f
                        val rectYScaleHalf: Float = rect.getYScale() / 2.0f

                        // Translate the circle so that it's in the first quadrant, with the center of the
                        // rectangle at (0,0).
                        val circleX = Math.abs(circleXWorld - rectXWorld)
                        val circleY = Math.abs(circleYWorld - rectYWorld)
                        // Circle is too far from rect edge(s) to overlap.  No collision.
                        if (circleX > rectXScaleHalf + radius || circleY > rectYScaleHalf + radius) {
                            return@run
                        }
                        /*
                         * Check to see if the center of the circle is inside the rect on one axis.  The
                         * previous test eliminated anything that was too far on either axis, so
                         * if this passes then we must have a collision.
                         *
                         * We're not moving the ball fast enough (limited by MAX_STEP) to get the center
                         * of the ball completely inside the rect (i.e. we shouldn't see a case where the
                         * center is inside the rect on *both* axes), so if we're inside in the X axis we
                         * can conclude that we just collided due to vertical motion, and have hit a
                         * horizontal surface.
                         *
                         * If the center isn't inside on either axis, we've hit the corner case, and
                         * need to do a distance test.
                         */if (circleX <= rectXScaleHalf) {
                        faceHit = HIT_FACE_HORIZONTAL
                        faceToAdjust = faceHit
                    } else if (circleY <= rectYScaleHalf) {
                        faceHit = HIT_FACE_VERTICAL
                        faceToAdjust = faceHit
                    } else {
                        // Check the distance from rect corner to center of circle.
                        val xdist = circleX - rectXScaleHalf
                        val ydist = circleY - rectYScaleHalf
                        if (xdist * xdist + ydist * ydist > radiusSq) {
                            // Not close enough.
                            //Log.d(TAG, "COL: corner miss");
                            return@run
                        }

                        /*
                         * The center point of the ball is outside both edges of the rectangle,
                         * but the corner is inside the radius of the circle, so this is a corner
                         * hit.  We need to decide how to bounce off.
                         *
                         * One approach is to see which edge is closest.  We know we're within a
                         * ball-radius of both edges.  If you imagine a ball moving straight upward,
                         * hitting just to the left of the bottom-left corner of a brick, you'll
                         * note that the impact occurs when the X distance (from brick edge to
                         * center of ball) is very small, and the Y distance is close to the ball
                         * radius.  So if X < Y, it's a horizontal-surface hit.
                         *
                         * However, there's a nasty edge case: imagine the ball is traveling up and
                         * to the right.  It skims past the top-left corner of a brick.  If the ball
                         * is positioned just barely outside the collision radius to the left of the
                         * brick in the current frame, our next step could take us to the other side
                         * of the ball -- at which point we "collide" with the horizontal *top*
                         * surface of the brick.  The brick is destroyed and the ball "bounces" down
                         * and to the right (because we reverse Y direction on a horizontal hit).
                         * Decreasing MAX_STEP makes this less likely, but we can't make it impossible.
                         *
                         * Another approach is to compare the direction the ball was moving with
                         * which corner we hit.  Consider the bottom-left corner of a brick.  There
                         * are three ways to hit it: straight in (ball moving up and right), skimming
                         * from the left (ball moving down and right), and skimming from below
                         * (ball moving up and left).  By comparing just the sign of the components
                         * of the ball's direction vector with the sign of a vector drawn from the
                         * corner to the center of the rect, we can decide what sort of impact
                         * we've had.
                         *
                         * If the signs match, it's a "sharp" corner impact, and we want to bounce
                         * straight back.  If only X matches, we're approaching from the side, and
                         * it's a vertical side impact.  If only Y matches, we're approaching from
                         * the bottom, and it's a horizontal impact.  The collision behavior no
                         * longer depends on which side we're actually touching, concealing the
                         * fact that the ball has effectively passed through the corner of the brick
                         * and we're catching the collision a bit late.
                         *
                         * If bouncing straight back off of a corner is undesirable, we can just
                         * use the computation done in the faceToAdjust assignment for "sharp
                         * "corner" impacts instead.
                         */
                        val dirXSign = Math.signum(dirX)
                        val dirYSign = Math.signum(dirY)
                        val cornerXSign = Math.signum(rectXWorld - circleXWorld)
                        val cornerYSign = Math.signum(rectYWorld - circleYWorld)
                        val msg: String
                        if (dirXSign == cornerXSign && dirYSign == cornerYSign) {
                            faceHit = HIT_FACE_SHARPCORNER
                            msg = "sharp"
                            if (DEBUG_COLLISIONS) {
                                // Sharp corners can be interesting.  Slow it down for a few
                                // seconds.
                                mDebugSlowMotionFrames = 240
                            }
                        } else if (dirXSign == cornerXSign) {
                            faceHit = HIT_FACE_VERTICAL
                            msg = "vert"
                        } else if (dirYSign == cornerYSign) {
                            faceHit = HIT_FACE_HORIZONTAL
                            msg = "horiz"
                        } else {
                            // This would mean we hit the far corner of the brick, i.e. the ball
                            // passed completely through it.
                            Log.w(TAG, "COL: impossible corner hit")
                            faceHit = HIT_FACE_SHARPCORNER
                            msg = "???"
                        }
                        if (DEBUG_COLLISIONS) {
                            Log.d(
                                TAG, "COL: " + msg + "-corner hit xd=" + xdist + " yd=" + ydist
                                        + " dir=" + dirXSign + "," + dirYSign
                                        + " cor=" + cornerXSign + "," + cornerYSign
                            )
                        }

                        // Adjust whichever requires the least movement to guarantee we're no
                        // longer colliding.
                        faceToAdjust = if (xdist < ydist) {
                            HIT_FACE_HORIZONTAL
                        } else {
                            HIT_FACE_VERTICAL
                        }
                    }
                        if (DEBUG_COLLISIONS) {
                            var msg = "?"
                            if (faceHit == HIT_FACE_SHARPCORNER) {
                                msg = "corner"
                            } else if (faceHit == HIT_FACE_HORIZONTAL) {
                                msg = "horiz"
                            } else if (faceHit == HIT_FACE_VERTICAL) {
                                msg = "vert"
                            }
                            Log.d(
                                TAG, "COL: $msg hit ${rect::class.simpleName}" +
                                        " cx=$circleXWorld cy=$circleYWorld rx=$rectXWorld ry=$rectYWorld rxh=$rectXScaleHalf ryh=$rectYScaleHalf"
                            )
                        }

                        /*
                         * Collision!
                         *
                         * Because we're moving in discrete steps rather than continuously, we will
                         * usually end up slightly embedded in the object.  If, after reversing direction,
                         * we subsequently step forward very slightly (assuming a non-destructable
                         * object like a wall), we will detect a second collision with the same object,
                         * and reverse direction back *into* the wall.  Visually, the ball will "stick"
                         * to the wall and vibrate.
                         *
                         * We need to back the ball out slightly.  Ideally we'd back it along the path
                         * the ball was traveling by just the right amount, but unless MAX_STEP is
                         * really large the difference between that and a minimum-distance axis-aligned
                         * shift is negligible -- and this is easier to compute.
                         *
                         * There's some risk that our adjustment will leave the ball trapped in a
                         * different object.  Since the ball is the only object that's moving, and the
                         * direction of adjustment shouldn't be too far from the angle of incidence, we
                         * shouldn't have this problem in practice.
                         *
                         * Note this leaves the ball just *barely* in contact with the object it hit,
                         * which means it's technically still colliding.  This won't cause us to
                         * collide again and reverse course back into the object because we will move
                         * the ball a nonzero distance away from the object before we check for another
                         * collision.  The use of MIN_STEP ensures that we won't fall victim to floating
                         * point round-off error.  (If we didn't want to guarantee movement, we could
                         * shift the ball a tiny bit farther so that it simply wasn't in contact.)
                         */
                        var hitXAdj: Float
                        var hitYAdj: Float
                        if (faceToAdjust == HIT_FACE_HORIZONTAL) {
                            hitXAdj = 0.0f
                            hitYAdj = rectYScaleHalf + radius - circleY
                            if (GameSurfaceRenderer.EXTRA_CHECK && hitYAdj < 0.0f) {
                                Log.e(TAG, "HEY: horiz was neg")
                            }
                            if (circleYWorld < rectYWorld) {
                                // ball is below rect, must be moving up, so adjust it down
                                hitYAdj = -hitYAdj
                            }
                        } else if (faceToAdjust == HIT_FACE_VERTICAL) {
                            hitXAdj = rectXScaleHalf + radius - circleX
                            hitYAdj = 0.0f
                            if (GameSurfaceRenderer.EXTRA_CHECK && hitXAdj < 0.0f) {
                                Log.e(TAG, "HEY: vert was neg")
                            }
                            if (circleXWorld < rectXWorld) {
                                // ball is left of rect, must be moving to right, so adjust it left
                                hitXAdj = -hitXAdj
                            }
                        } else {
                            Log.w(TAG, "GLITCH: unexpected faceToAdjust $faceToAdjust")
                            hitYAdj = 0.0f
                            hitXAdj = hitYAdj
                        }
                        if (DEBUG_COLLISIONS) {
                            Log.d(
                                TAG, "COL:  r=" + radius + " trav=" + traveled +
                                        " xadj=" + hitXAdj + " yadj=" + hitYAdj
                            )
                        }
                        mHitFace = faceHit
                        mHitDistanceTraveled = traveled
                        mHitXAdj = hitXAdj
                        mHitYAdj = hitYAdj
                        return rect
                    }
                }

            }
        }

        //Log.d(TAG, "COL: no collision");
        return null
    }

    /**
     * Game state storage.  Anything interesting gets copied in here.  If we wanted to save it
     * to disk we could just serialize the object.
     *
     *
     * This is "organized" as a dumping ground for GameState to use.
     */
    private class SavedGame {
        lateinit var mLiveBricks: BooleanArray
        var mBallXDirection = 0f
        var mBallYDirection = 0f
        var mBallXPosition = 0f
        var mBallYPosition = 0f
        var mBallSpeed = 0
        var mPaddlePosition = 0f
        var mGamePlayState = 0
        var mGameStatusMessageNum = 0
        var mLivesRemaining = 0
        var mScore = 0
        var mIsValid = false // set when state has been written out
    }
}
