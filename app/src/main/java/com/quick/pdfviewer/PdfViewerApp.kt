/*
 * Copyright 2026 Quick PDF Viewer Contributors
 * Licensed under the Apache License, Version 2.0
 * See the LICENSE file in the root directory for details.
 */

package com.quick.pdfviewer

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import java.text.DateFormat
import java.util.Date
import java.io.File

private const val PDF_URI_ARGUMENT = "pdf_uri"
private const val ABOUT_PDF_VERSION = "1.0.0-beta01"
private const val PDF_FRAGMENT_TAG = "quick_pdf_viewer_fragment"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerApp(
    documentUri: Uri,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val pdfFragment = activity?.supportFragmentManager?.findFragmentByTag(PDF_FRAGMENT_TAG)
        as? QuickPdfViewerFragment

    val fileName = remember(documentUri) { queryFileName(context, documentUri) }
    var menuExpanded by remember { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<ViewerDialog?>(null) }

    BackHandler(onBack = onExit)
    TopAppBar(
        title = {
            Text(
                text = fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "Back",
                )
            }
        },
        actions = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.more_vert_24px),
                    contentDescription = "More options",
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(painterResource(R.drawable.share_24px), null) },
                    onClick = {
                        menuExpanded = false
                        sharePdf(context, documentUri, fileName)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Go to page") },
                    leadingIcon = { Icon(painterResource(R.drawable.arrow_outward_24px), null) },
                    onClick = {
                        menuExpanded = false
                        activeDialog = ViewerDialog.GoToPage
                    },
                )
                DropdownMenuItem(
                    text = { Text("Find text") },
                    leadingIcon = { Icon(painterResource(R.drawable.search_24px), null) },
                    enabled = pdfFragment?.isDocumentLoaded == true,
                    onClick = {
                        menuExpanded = false
                        pdfFragment?.startTextSearch()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Properties") },
                    leadingIcon = { Icon(painterResource(R.drawable.article_24px), null) },
                    onClick = {
                        menuExpanded = false
                        activeDialog = ViewerDialog.Properties
                    },
                )
                DropdownMenuItem(
                    text = { Text("About app") },
                    leadingIcon = { Icon(painterResource(R.drawable.info_24px), null) },
                    onClick = {
                        menuExpanded = false
                        activeDialog = ViewerDialog.About
                    },
                )
                DropdownMenuItem(
                    text = { Text("Exit") },
                    leadingIcon = { Icon(painterResource(R.drawable.close_24px), null) },
                    onClick = {
                        menuExpanded = false
                        onExit()
                    },
                )
            }
        },
    )

    when (activeDialog) {
        ViewerDialog.GoToPage -> {
            GoToPageDialog(
                pageCount = pdfFragment?.pageCount ?: 0,
                currentPage = (pdfFragment?.firstVisiblePage ?: 0) + 1,
                onDismiss = { activeDialog = null },
                onGo = { page ->
                    activeDialog = null
                    pdfFragment?.goToPage(page)
                },
            )
        }

        ViewerDialog.Properties -> {
            FilePropertiesDialog(
                context = context,
                uri = documentUri,
                fileName = fileName,
                pageCount = pdfFragment?.pageCount,
                onDismiss = { activeDialog = null },
            )
        }

        ViewerDialog.About -> {
            AboutDialog(onDismiss = { activeDialog = null })
        }

        null -> Unit
    }
}

@OptIn(ExperimentalPdfApi::class)
class QuickPdfViewerFragment : PdfViewerFragment() {
    var isDocumentLoaded by mutableStateOf(false)
        private set

    var pageCount by mutableIntStateOf(0)
        private set

    var firstVisiblePage by mutableIntStateOf(0)
        private set

    private var viewerView: PdfView? = null
    private var viewportListener: PdfView.OnViewportChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(PDF_URI_ARGUMENT)?.let { uriString ->
            documentUri = uriString.toUri()
        }
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        pageCount = document.pageCount
        firstVisiblePage = viewerView?.firstVisiblePage ?: 0
        isDocumentLoaded = true
    }

    override fun onLoadDocumentError(error: Throwable) {
        isDocumentLoaded = false
        pageCount = 0
        super.onLoadDocumentError(error)
    }

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        viewportListener?.let { previousListener ->
            viewerView?.removeOnViewportChangedListener(previousListener)
        }

        viewerView = pdfView
        firstVisiblePage = pdfView.firstVisiblePage
        viewportListener = object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: android.util.SparseArray<android.graphics.RectF>,
                zoomLevel: Float,
            ) {
                this@QuickPdfViewerFragment.firstVisiblePage = firstVisiblePage
            }
        }
        pdfView.addOnViewportChangedListener(viewportListener!!)
    }

    override fun onDestroyView() {
        viewerView?.let { view ->
            viewportListener?.let(view::removeOnViewportChangedListener)
        }
        viewportListener = null
        viewerView = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        isDocumentLoaded = false
        pageCount = 0
        super.onDestroy()
    }

    fun goToPage(page: Int) {
        if (page in 1..pageCount) {
            viewerView?.scrollToPage(page - 1)
        }
    }

    fun startTextSearch() {
        if (isDocumentLoaded && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            isTextSearchActive = true
        }
    }

    companion object {
        fun newInstance(documentUri: Uri): QuickPdfViewerFragment =
            QuickPdfViewerFragment().apply {
                arguments = Bundle().apply {
                    putString(
                        PDF_URI_ARGUMENT,
                        documentUri.toString(),
                    )
                }
            }
    }
}

private enum class ViewerDialog {
    GoToPage,
    Properties,
    About,
}

@Composable
private fun GoToPageDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var pageValue by remember(currentPage) {
        mutableStateOf(TextFieldValue(
                text = currentPage.toString(),
                selection = TextRange(0, currentPage.toString().length),
            )
        )
    }
    val page = pageValue.text.toIntOrNull()
    val valid = pageCount > 0 && page != null && page in 1..pageCount
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = pageValue,
                onValueChange = { newValue ->
                    val digitsOnly = newValue.text
                        .filter(Char::isDigit)
                        .take(8)

                    pageValue = if (digitsOnly == newValue.text) {
                        newValue
                    } else {
                        TextFieldValue(
                            text = digitsOnly,
                            selection = TextRange(digitsOnly.length),
                        )
                    }
                },
                modifier = Modifier.focusRequester(focusRequester),
                label = { Text("Page number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                supportingText = { Text("Total page: $pageCount") },
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onGo(page!!) }) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun FilePropertiesDialog(
    context: android.content.Context,
    uri: Uri,
    fileName: String,
    pageCount: Int?,
    onDismiss: () -> Unit,
) {
    val properties = remember(uri, fileName, pageCount) {
        queryFileProperties(context, uri, fileName, pageCount)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File properties") },
        text = {
            LazyColumn {
                items(properties) { property ->
                    PropertyRow(property.first, property.second)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick PDF Viewer") },
        text = {
            Column {
                Text("A lightweight PDF viewer for opening PDF files from other android apps.")
                Spacer(Modifier.height(8.dp))
                Text("Version ${BuildConfig.VERSION_NAME}")
                Spacer(Modifier.height(8.dp))
                Text("PDF rendering: AndroidX PDF $ABOUT_PDF_VERSION")
                Spacer(Modifier.height(8.dp))
                val annotatedLinkString = buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://github.com/purebliss888/quick-pdf-viewer",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) {append("Source code")}
                }
                Text(text = annotatedLinkString)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun sharePdf(context: android.content.Context, uri: Uri, fileName: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, fileName)
        clipData = ClipData.newRawUri(fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share PDF"))
}

private fun queryFileName(context: android.content.Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Document.pdf"
}

private fun queryFileProperties(
    context: android.content.Context,
    uri: Uri,
    fileName: String,
    pageCount: Int?,
): List<Pair<String, String>> {
    var size: Long? = null
    var lastModified: Long? = null

    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val file = File(requireNotNull(uri.path))

        size = file.length()
        lastModified = file.lastModified()
    } else {

        val projection: Array<String>? = null
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                size =
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                val modifiedIndex =
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                lastModified =
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        null
                    }
            }
        }
    }
    val dateText = lastModified?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Unknown"

    return listOf(
        "Name" to fileName,
        "Size" to (size?.let { Formatter.formatFileSize(context, it) } ?: "Unknown"),
        "Pages" to (pageCount?.takeIf { it > 0 }?.toString() ?: "Unknown"),
        "Modified" to dateText,
        "Location" to getDisplayPath(context, uri),
    )
}

private fun getDisplayPath(
    context: android.content.Context,
    uri: Uri,
): String {
    return when {
        uri.scheme == "file" -> {
            uri.path ?: uri.toString()
        }

        DocumentsContract.isDocumentUri(context, uri) -> {
            runCatching {
                DocumentsContract.findDocumentPath(
                    context.contentResolver,
                    uri,
                )?.path
                    ?.joinToString("/")
            }.getOrNull()
                ?: uri.toString()
        }

        else -> {
            uri.toString()
        }
    }
}