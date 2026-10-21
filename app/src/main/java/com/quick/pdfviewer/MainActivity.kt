
/*
 * Copyright 2026 Quick PDF Viewer Contributors
 * Licensed under the Apache License, Version 2.0
 * See the LICENSE file in the root directory for details.
 */

package com.quick.pdfviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat

private const val PDF_FRAGMENT_TAG = "quick_pdf_viewer_fragment"

class MainActivity : AppCompatActivity() {
    var isTopBarVisible by mutableStateOf(true)
        private set

    var topBarAutoHideEnabled by mutableStateOf(false)
        private set

    fun toggleTopBar() {
        isTopBarVisible = !isTopBarVisible
    }

    fun enableTopBarAutoHide() {
        topBarAutoHideEnabled = true
        isTopBarVisible = true
    }

    fun hideTopBar() {
        isTopBarVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val documentUri = intent?.data
        if (!isSupportedPdfIntent(intent, documentUri)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        findViewById<ComposeView>(R.id.compose_view).setContent {
            AppTheme {
                PdfViewerApp(
                    documentUri = documentUri!!,
                    onExit = { finishAndRemoveTask() },
                )
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.pdf_viewer_container,
                    QuickPdfViewerFragment.newInstance(documentUri!!),
                    PDF_FRAGMENT_TAG,
                )
                .commit()
        }
    }

    private fun isSupportedPdfIntent(intent: Intent?, uri: Uri?): Boolean {
        if (uri == null) return false
        return intent?.action == Intent.ACTION_VIEW &&
            (intent.type == null || intent.type == "application/pdf" ||
                contentResolver.getType(uri) == "application/pdf")
    }
}
