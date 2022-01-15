/*
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frank.breakout.opengl

import android.graphics.Rect
import android.opengl.GLES20
import com.frank.breakout.opengl.base.TexturedAlignedRect
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Ball object.
 */
class Ball : TexturedAlignedRect() {
    /**
     * Gets the motion vector X component.
     */
    // Normalized motion vector.
    var xDirection = 0f
        private set

    /**
     * Gets the motion vector Y component.
     */
    var yDirection = 0f
        private set

    // Speed, expressed in terms of steps per second.  A speed of 60 will move the ball
    // 60 arena-units per second, or 1 unit per frame on a 60Hz device.  This is not the same
    // as 1 *pixel* per frame unless the arena units happen to match up.
    private var mSpeed = 0

    /**
     * Sets the motion vector.  Input values will be normalized.
     */
    fun setDirection(deltaX: Float, deltaY: Float) {
        val mag = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble())
            .toFloat()
        xDirection = deltaX / mag
        yDirection = deltaY / mag
    }
    /**
     * Gets the speed, in arena-units per second.
     */
    /**
     * Sets the speed, in arena-units per second.
     */
    var speed: Int
        get() = mSpeed
        set(speed) {
            if (speed <= 0) {
                throw RuntimeException("speed must be positive ($speed)")
            }
            mSpeed = speed
        }// The "scale" value indicates diameter.

    /**
     * Gets the ball's radius, in arena units.
     */
    val radius: Float
        get() =// The "scale" value indicates diameter.
            getXScale() / 2.0f

    /**
     * Generates the ball texture.  This is a simple filled circle in a solid color, with
     * a transparent black background.
     *
     * @return A direct ByteBuffer with pre-multiplied RGBA data.
     */
    private fun generateBallTexture(): ByteBuffer {
        /*
         * Most images used in games are generated with external tools and then loaded from
         * image files.  This is an example of generating texture data directly.
         *
         * We "render" it into a byte[], then copy that into a direct byte buffer.  This
         * requires one extra copy than we would need if we rendered directly into the ByteBuffer,
         * but we can't assume that ByteBuffer.array() will work with direct byte buffers, and
         * writing data with ByteBuffer.put(int, byte) is slow and annoying.
         *
         * We use GL_RGBA, which has four 8-bit normalized unsigned integer components (which
         * is a fancy way to say, "the usual format for 32-bit color pixels").  We could
         * get away with creating this as an alpha map and then use a shader to apply color,
         * but that's not necessary and requires the shader work.
         */
        val buf = ByteArray(TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL)

        /*
         * We're drawing a filled circle with a radius of 31, which gives us a circle
         * that fills a 63x63 area.  We're using a 64x64 texture, so have a choice to make:
         *  (1) Assume the hardware can handle non-power-of-2 texture sizes.  This doesn't
         *      always hold, so we don't want to do this.
         *  (2) Leave the 64th row and column set to transparent black, and hope nobody notices
         *      when things don't quite collide.  This is reasonably safe, given the size of
         *      the ball and the speed of motion.
         *  (3) "Stretch" the circle slightly when generating the data, doubling-up the center
         *      row and column, to fill the circle to 64x64.  Should look fine.
         *  (4) Adjust the texture coordinates so that the edges are at 0.984375 (63/64) instead
         *      of 1.0.  This is generally the correct approach, but requires that we manually
         *      specify the texture dimensions instead of just saying, "use this whole image".
         *
         * Going with #4.  Note the radius of 31 is arbitrary and has no bearing on how large
         * the ball is on screen (this is a texture applied to a pair of triangles, not a bitmap
         * of screen-sized pixels).  We want it to be small enough that it doesn't use up a
         * ton of memory, but bug enough that, if the ball is drawn very large, the circle
         * edges don't look chunky when we scale it up.
         */
        val left = IntArray(TEX_SIZE - 1)
        val right = IntArray(TEX_SIZE - 1)
        computeCircleEdges(TEX_SIZE / 2 - 1, left, right)

        // Render the edge list as a filled circle.
        for (y in left.indices) {
            val xleft = left[y]
            val xright = right[y]
            for (x in xleft..xright) {
                val offset = (y * TEX_SIZE + x) * BYTES_PER_PIXEL
                buf[offset] = 0xff.toByte() // red
                buf[offset + 1] = 0xff.toByte() // green
                buf[offset + 2] = 0xff.toByte() // blue
                buf[offset + 3] = 0xff.toByte() // alpha
            }
        }

        // Create a ByteBuffer, copy the data over, and (very important) reset the position.
        val byteBuf = ByteBuffer.allocateDirect(buf.size)
        byteBuf.put(buf)
        byteBuf.position(0)
        return byteBuf
    }

    /**
     * Generates a test texture.  We want to create a 4x4 block pattern with obvious color
     * values in the corners, so that we can confirm orientation and coverage.  We also
     * leave a couple of alpha holes to check that channel.
     *
     * Like most image formats, the pixel data begins with the top-left corner, which is
     * upside-down relative to OpenGL conventions.  The texture coordinates should be flipped
     * vertically.  Using an asymmetric patterns lets us check that we're doing that right.
     *
     * Colors use pre-multiplied alpha (so set glBlendFunc appropriately).
     *
     * @return A direct ByteBuffer with the 8888 RGBA data.
     */
    private fun generateTestTexture(): ByteBuffer {
        val buf = ByteArray(TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL)
        val scale = TEX_SIZE / 4 // convert 64x64 --> 4x4
        var i = 0
        while (i < buf.size) {
            val texRow = i / BYTES_PER_PIXEL / TEX_SIZE
            val texCol = i / BYTES_PER_PIXEL % TEX_SIZE
            val gridRow = texRow / scale // 0-3
            val gridCol = texCol / scale // 0-3
            val gridIndex = gridRow * 4 + gridCol // 0-15
            var color = GRID[gridIndex]

            // override the pixels in two corners to check coverage
            if (i == 0) {
                color = OPAQUE or WHITE
            } else if (i == buf.size - BYTES_PER_PIXEL) {
                color = OPAQUE or WHITE
            }

            // extract RGBA; use "int" instead of "byte" to get unsigned values
            val red = color and 0xff
            val green = color shr 8 and 0xff
            val blue = color shr 16 and 0xff
            val alpha = color shr 24 and 0xff

            // pre-multiply colors and store in buffer
            val alphaM = alpha / 255.0f
            buf[i] = (red * alphaM).toByte()
            buf[i + 1] = (green * alphaM).toByte()
            buf[i + 2] = (blue * alphaM).toByte()
            buf[i + 3] = alpha.toByte()
            i += BYTES_PER_PIXEL
        }
        val byteBuf = ByteBuffer.allocateDirect(buf.size)
        byteBuf.put(buf)
        byteBuf.position(0)
        return byteBuf
    }

    companion object {
        private const val TEX_SIZE = 64 // dimension for square texture (power of 2)
        private const val DATA_FORMAT = GLES20.GL_RGBA // 8bpp RGBA
        private const val BYTES_PER_PIXEL = 4

        /**
         * Computes the left and right edges of a rasterized circle, using Bresenham's algorithm.
         *
         * @param rad Radius.
         * @param left Left edge index, range [0, rad].  Array must hold (rad*2+1) elements.
         * @param right Right edge index, range [rad, rad*2 + 1].
         */
        private fun computeCircleEdges(rad: Int, left: IntArray, right: IntArray) {
            /* (also available in 6502 assembly) */
            var d: Int = 1 - rad
            var x: Int = 0
            var y: Int = rad

            // Walk through one quadrant, setting the other three as reflections.
            while (x <= y) {
                setCircleValues(rad, x, y, left, right)
                if (d < 0) {
                    d += (x shl 2) + 3
                } else {
                    d += (x - y shl 2) + 5
                    y--
                }
                x++
            }
        }

        /**
         * Sets the edge values for four quadrants based on values from the first quadrant.
         */
        private fun setCircleValues(rad: Int, x: Int, y: Int, left: IntArray, right: IntArray) {
            left[rad - y] = rad - x
            left[rad + y] = left[rad - y]
            left[rad - x] = rad - y
            left[rad + x] = left[rad - x]
            right[rad - y] = rad + x
            right[rad + y] = right[rad - y]
            right[rad - x] = rad + y
            right[rad + x] = right[rad - x]
        }

        // Colors for the test texture, in little-endian RGBA.
        private const val BLACK = 0x00000000
        private const val RED = 0x000000ff
        private const val GREEN = 0x0000ff00
        private const val BLUE = 0x00ff0000
        private const val MAGENTA = RED or BLUE
        private const val YELLOW = RED or GREEN
        private const val CYAN = GREEN or BLUE
        private const val WHITE = RED or GREEN or BLUE
        private const val OPAQUE = 0xff000000L.toInt()
        private const val HALF = 0x80000000L.toInt()
        private const val LOW = 0x40000000L.toInt()
        private const val TRANSP = 0
        val GRID = intArrayOf( // must be 16 elements
            OPAQUE or RED, OPAQUE or YELLOW, OPAQUE or GREEN, OPAQUE or MAGENTA,
            OPAQUE or WHITE, LOW or RED, LOW or GREEN, OPAQUE or YELLOW,
            OPAQUE or MAGENTA, TRANSP or GREEN, HALF or RED, OPAQUE or BLACK,
            OPAQUE or CYAN, OPAQUE or MAGENTA, OPAQUE or CYAN, OPAQUE or BLUE
        )
    }

    init {
        setTexture(generateBallTexture(), TEX_SIZE, TEX_SIZE, DATA_FORMAT)
        // Ball diameter is an odd number of pixels.
        setTextureCoords(Rect(0, 0, TEX_SIZE - 1, TEX_SIZE - 1))
    }
}