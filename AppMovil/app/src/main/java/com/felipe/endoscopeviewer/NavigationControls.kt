package com.felipe.endoscopeviewer

import android.view.ViewGroup
import android.widget.Button

/** Coloca la accion de volver inmediatamente despues de la accion principal. */
fun placeBackButtonBelowNext(previousButton: Button, nextButton: Button) {
    val parent = previousButton.parent as? ViewGroup ?: return
    if (nextButton.parent !== parent) return

    parent.removeView(previousButton)
    parent.addView(previousButton, parent.indexOfChild(nextButton) + 1)
}
