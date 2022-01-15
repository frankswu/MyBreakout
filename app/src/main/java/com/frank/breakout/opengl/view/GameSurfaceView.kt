package com.frank.breakout.opengl.view

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.ConditionVariable
import android.util.Log
import android.view.MotionEvent
import com.frank.breakout.controller.GameState
import com.frank.breakout.activity.BreakoutActivity.Companion.TAG
import com.frank.breakout.activity.GameActivity
import com.frank.breakout.opengl.base.TextResources


class GameSurfaceView(context: Context?) : GLSurfaceView(context) {

    constructor(
        context: GameActivity,
        gameState: GameState,
        textConfig: TextResources.Configuration
    ) : this(context) {
        setEGLContextClientVersion(2) // Request OpenGL ES 2.0

        // Create our Renderer object, and tell the GLSurfaceView code about it.  This also
        // starts the renderer thread, which will be calling the various callback methods
        // in the GameSurfaceRenderer class.
        mRenderer = GameSurfaceRenderer(gameState, this, textConfig)
        setRenderer(mRenderer)

    }


    private var mRenderer: GameSurfaceRenderer? = null
    private val syncObj = ConditionVariable()


    override fun onPause() {
        /*
         * We call a "pause" function in our Renderer class, which tells it to save state and
         * go to sleep.  Because it's running in the Renderer thread, we call it through
         * queueEvent(), which doesn't wait for the code to actually execute.  In theory the
         * application could be killed shortly after we return from here, which would be bad if
         * it happened while the Renderer thread was still saving off important state.  We need
         * to wait for it to finish.
         */
        super.onPause()

        Log.d(TAG, "asking renderer to pause");
        syncObj.close()
        queueEvent { mRenderer?.onViewPause(syncObj) }
        syncObj.block()

        Log.d(TAG, "renderer pause complete");
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        /*
         * Forward touch events to the game loop.  We don't want to call Renderer methods
         * directly, because they manipulate state that is "owned" by a different thread.  We
         * use the GLSurfaceView queueEvent() function to execute it there.
         *
         * This increases the latency of our touch response slightly, but it shouldn't be
         * noticeable.
         */
        when (e.action) {
            MotionEvent.ACTION_MOVE -> {
                val x: Float = e.x
                val y: Float = e.y
                Log.d(TAG, "GameSurfaceView onTouchEvent x=$x y=$y");
                queueEvent { mRenderer?.touchEvent(x, y) }
            }
            else -> {
            }
        }
        return true
    }
}
