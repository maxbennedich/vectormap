package com.max.vectormap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class TextRenderer {
    private static final String ALPHABET = "|{}Å@ÄÖå$/\\äö()Q[]j#!&?ABCDEFGHIJKLMNOPRSTUVWXYZbdfhiklt%0123456789;gpqy+:acemnorsuvwxz<>=\"'*^,~.-_`";
    private static final int[] CHAR_POS = {1,1,11,62,11,-43, 12,1,37,58,4,-41, 38,1,63,58,3,-41, 64,1,90,57,1,-51, 91,1,147,55,2,-41, 148,1,174,53,1,-47, 175,1,201,53,2,-47,
            202,1,227,53,1,-47, 228,1,253,51,1,-42, 254,1,280,50,-4,-39, 281,1,307,50,-2,-39, 308,1,333,48,1,-42, 334,1,359,48,1,-42, 360,1,377,48,2,-39,
            378,1,395,48,1,-39, 396,1,423,48,2,-39, 424,1,442,48,2,-39, 443,1,461,48,-1,-39, 462,1,477,48,0,-39, 1,63,46,109,1,-41, 47,63,61,107,2,-39,
            62,63,91,107,0,-39, 92,63,116,107,0,-39, 117,63,143,107,1,-39, 144,63,170,107,2,-39, 171,63,197,107,2,-39, 198,63,224,107,2,-39, 225,63,248,107,2,-39,
            249,63,271,107,2,-39, 272,63,298,107,2,-39, 299,63,325,107,2,-39, 326,63,340,107,2,-39, 341,63,367,107,0,-39, 368,63,394,107,2,-39, 395,63,417,107,2,-39,
            418,63,457,107,2,-39, 458,63,484,107,2,-39, 485,63,511,107,2,-39, 1,110,27,154,2,-39, 28,110,55,154,2,-39, 56,110,83,154,1,-39, 84,110,108,154,0,-39,
            109,110,135,154,2,-39, 136,110,162,154,1,-39, 163,110,202,154,1,-39, 203,110,229,154,1,-39, 230,110,256,154,0,-39, 257,110,282,154,0,-39,
            283,110,308,154,2,-39, 309,110,334,154,1,-39, 335,110,351,154,1,-39, 352,110,376,154,2,-39, 377,110,390,154,2,-39, 391,110,415,154,2,-39,
            416,110,429,154,2,-39, 430,110,446,154,1,-39, 447,110,476,153,0,-38, 477,110,502,153,2,-38, 1,155,20,198,-1,-38, 21,155,47,198,0,-38,
            48,155,73,198,1,-38, 74,155,101,198,1,-38, 102,155,128,198,1,-38, 129,155,154,198,2,-38, 155,155,181,198,-1,-38, 182,155,208,198,1,-38,
            209,155,235,198,1,-38, 236,155,249,197,2,-34, 250,155,276,197,0,-34, 277,155,302,197,2,-34, 303,155,328,197,1,-34, 329,155,353,197,2,-34,
            354,155,392,194,6,-34, 393,155,406,194,2,-34, 407,155,432,194,1,-34, 433,155,458,194,1,-34, 459,155,484,194,1,-34, 1,199,37,238,2,-34,
            38,199,62,238,2,-34, 63,199,88,238,1,-34, 89,199,113,238,2,-34, 114,199,139,238,1,-34, 140,199,164,238,2,-34, 165,199,189,238,2,-34,
            190,199,225,238,2,-34, 226,199,251,238,1,-34, 252,199,275,238,0,-34, 276,199,313,236,7,-33, 314,199,351,236,7,-33, 352,199,390,221,6,-26,
            391,199,410,220,2,-40, 411,199,421,220,2,-40, 422,199,444,220,0,-39, 445,199,483,219,11,-40, 484,199,497,216,2,-9, 1,239,43,254,4,-22,
            44,239,57,253,2,-9, 58,239,83,251,1,-21, 84,239,117,250,0,7, 118,239,131,250,8,-42};

    private int textureWidth, textureHeight; // will be powers of 2
//    private static final int FORMAT = GLES20.GL_RGBA;
//    private static final int BYTES_PER_PIXEL = 4;   // RGBA

    private int fontTextureHandle;

    private int fontProgram;

    /** Scaling factors to make the text look the same regardless of screen orientation. */
    public float xScale = 1, yScale = 1;

    public TextRenderer(Context context) {
        loadTexture(context, R.drawable.font_56_512_256);
    }

    void loadTexture(Context context, int resourceId) {
        int[] textureHandle = new int[1];

        // TODO more compact format? 8 bit color + 8 bit alpha?

        GLES20.glGenTextures(1, textureHandle, 0);

        if (textureHandle[0] == 0)
            throw new RuntimeException("Error loading texture.");
        fontTextureHandle = textureHandle[0];

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false; // No pre-scaling

        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resourceId, options);
        textureWidth = bitmap.getWidth();
        textureHeight = bitmap.getHeight();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();

        String vertexShader = Common.readInputStream(context.getResources().openRawResource(R.raw.vertex_shader_font_2d));
        String fragmentShader = Common.readInputStream(context.getResources().openRawResource(R.raw.fragment_shader_font));
        fontProgram = ShaderHelper.createProgram(
                ShaderHelper.loadShader(GLES20.GL_VERTEX_SHADER, vertexShader),
                ShaderHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader));
    }

    static final int COORDS_PER_VERTEX = 3;
    static float vertexCoords[];

    static final int TEX_COORDS_PER_VERTEX = 2;
    static float texCoords[];

    private short drawOrder[]; // order to draw vertices

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordsBuffer;
    private ShortBuffer drawListBuffer;

    /** x/y range -1 to 1, with (-1,1) is top left corner */
    public void drawText(String text, float x, float y, float size, float[] color) {
        // order: top left, bottom left, bottom right, top right
        vertexCoords = new float[text.length() * 4 * COORDS_PER_VERTEX];
        texCoords = new float[text.length() * 4 * TEX_COORDS_PER_VERTEX];
        drawOrder = new short[text.length() * 6];

//        float vx = -0.98f, vy = 0.93f;
        float vx = x, vy = y;
        for (int k = 0; k < text.length(); ++k) {
            int ch = text.charAt(k);
            int chIdx = ALPHABET.indexOf(ch) * 6;
            if (chIdx < 0) {
                vx += size * xScale * 0.0018f;
                continue;
            }

            float chWidth = size * xScale * 0.0001f * (CHAR_POS[chIdx + 2] - CHAR_POS[chIdx]);
            float chHeight = size * yScale * 0.0001f * (CHAR_POS[chIdx + 3] - CHAR_POS[chIdx + 1]);
            float chXOfs = size * xScale * 0.0001f * CHAR_POS[chIdx + 4];
            float chYOfs = - size * yScale * 0.0001f * CHAR_POS[chIdx + 5];

            vertexCoords[k*12+0] = vx + chXOfs;
            vertexCoords[k*12+1] = vy + chYOfs;
            vertexCoords[k*12+3] = vx + chXOfs;
            vertexCoords[k*12+4] = vy - chHeight + chYOfs;
            vertexCoords[k*12+6] = vx + chWidth + chXOfs;
            vertexCoords[k*12+7] = vy - chHeight + chYOfs;
            vertexCoords[k*12+9] = vx + chWidth + chXOfs;
            vertexCoords[k*12+10] = vy + chYOfs;
            vertexCoords[k*12+2] = vertexCoords[k*12+5] = vertexCoords[k*12+8] = vertexCoords[k*12+11] = 0;

            texCoords[k*8+0] = (float) CHAR_POS[chIdx] / textureWidth;
            texCoords[k*8+1] = (float) CHAR_POS[chIdx + 1] / textureHeight;
            texCoords[k*8+2] = (float) CHAR_POS[chIdx] / textureWidth;
            texCoords[k*8+3] = (float) CHAR_POS[chIdx + 3] / textureHeight;
            texCoords[k*8+4] = (float) CHAR_POS[chIdx + 2] / textureWidth;
            texCoords[k*8+5] = (float) CHAR_POS[chIdx + 3] / textureHeight;
            texCoords[k*8+6] = (float) CHAR_POS[chIdx + 2] / textureWidth;
            texCoords[k*8+7] = (float) CHAR_POS[chIdx + 1] / textureHeight;

            drawOrder[k*6+0] = (short)(k*4 + 0);
            drawOrder[k*6+1] = (short)(k*4 + 1);
            drawOrder[k*6+2] = (short)(k*4 + 2);
            drawOrder[k*6+3] = (short)(k*4 + 0);
            drawOrder[k*6+4] = (short)(k*4 + 2);
            drawOrder[k*6+5] = (short)(k*4 + 3);

            vx += chWidth;
        }

        // initialize vertex byte buffer for shape coordinates
        ByteBuffer bb = ByteBuffer.allocateDirect(vertexCoords.length * Constants.BYTES_IN_FLOAT);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertexCoords);
        vertexBuffer.position(0);

        // initialize texture byte buffer
        ByteBuffer tbb = ByteBuffer.allocateDirect(texCoords.length * Constants.BYTES_IN_FLOAT);
        tbb.order(ByteOrder.nativeOrder());
        texCoordsBuffer = tbb.asFloatBuffer();
        texCoordsBuffer.put(texCoords);
        texCoordsBuffer.position(0);

        // initialize byte buffer for the draw list
        ByteBuffer dlb = ByteBuffer.allocateDirect(drawOrder.length * Constants.BYTES_IN_SHORT);
        dlb.order(ByteOrder.nativeOrder());
        drawListBuffer = dlb.asShortBuffer();
        drawListBuffer.put(drawOrder);
        drawListBuffer.position(0);

        GLES20.glUseProgram(fontProgram);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        int textureHandle = GLES20.glGetUniformLocation(fontProgram, "uTexture");
        int texCoordinateHandle = GLES20.glGetAttribLocation(fontProgram, "aTexCoordinate");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fontTextureHandle);
        GLES20.glUniform1i(textureHandle, 0);

        int positionHandle = GLES20.glGetAttribLocation(fontProgram, "vPosition");
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glVertexAttribPointer(texCoordinateHandle, TEX_COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, texCoordsBuffer);
        GLES20.glEnableVertexAttribArray(texCoordinateHandle);

        int mColorHandle = GLES20.glGetUniformLocation(fontProgram, "vColor");
        GLES20.glUniform4fv(mColorHandle, 1, color, 0);

//        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        GLES20.glDrawElements(
                GLES20.GL_TRIANGLES, drawOrder.length,
                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    public void adjustForScreenSize(int width, int height) {
        // make font appear the same size regardless of screen orientation
        if (width < height) {
            // portrait mode
            xScale = 1;
            yScale = (float)width / height;
        } else {
            // landscape mode
            xScale = (float)height / width;
            yScale = 1;
        }
    }
}
