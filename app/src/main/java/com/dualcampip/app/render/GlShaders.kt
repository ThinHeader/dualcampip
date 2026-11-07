package com.dualcampip.app.render

internal object GlShaders {
    const val VERTEX = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        attribute vec2 aLocal;
        varying vec2 vTexCoord;
        varying vec2 vLocal;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
            vLocal = aLocal;
        }
    """

    const val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        varying vec2 vLocal;
        uniform samplerExternalOES sTexture;
        uniform float uMask;
        uniform vec2 uSize;
        uniform float uRadius;
        void main() {
            vec4 color = texture2D(sTexture, vTexCoord);
            if (uMask > 0.5) {
                vec2 p = vLocal * uSize;
                vec2 halfSize = uSize * 0.5;
                vec2 q = abs(p - halfSize) - (halfSize - vec2(uRadius));
                float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
                float alpha = 1.0 - smoothstep(-0.8, 0.8, dist);
                if (alpha <= 0.01) discard;
                color.a *= alpha;
            }
            gl_FragColor = color;
        }
    """
}
