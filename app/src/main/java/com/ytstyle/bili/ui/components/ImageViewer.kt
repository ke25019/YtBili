package com.ytstyle.bili.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Full-screen image viewer: pinch to zoom, drag to pan, tap to close, long-press
 * for 保存 / 分享.
 *
 * A [Dialog] rather than an overlay inside the caller's layout, because both
 * callers are lazy lists — an overlay there would be recycled away mid-gesture.
 *
 * [startIndex] picks the first image shown; with more than one, 上一张 / 下一张
 * step through them. A pager was avoided deliberately: the transform gestures
 * and a horizontal pager fight over the same drags.
 */
@Composable
fun ImageViewer(
    urls: List<String>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (urls.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, urls.lastIndex)) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // A new image starts un-zoomed; carrying the previous zoom over reads as a
    // bug rather than a preference.
    LaunchedEffect(index) {
        scale = 1f
        offset = Offset.Zero
        menuOpen = false
    }

    fun withBitmap(action: (Bitmap) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val bitmap = loadBitmap(context, urls[index])
            busy = false
            menuOpen = false
            if (bitmap == null) {
                Toast.makeText(context, "图片加载失败", Toast.LENGTH_SHORT).show()
            } else {
                action(bitmap)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            withBitmap { saveToGallery(context, it) }
        } else {
            Toast.makeText(context, "没有存储权限，无法保存图片", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000))
                // Tap closes, long-press opens the menu. Both live here rather
                // than on the image so a pan that starts on the picture cannot
                // swallow the dismiss gesture.
                .pointerInput(urls.size) {
                    detectTapGestures(
                        onTap = { if (menuOpen) menuOpen = false else onDismiss() },
                        onLongPress = { menuOpen = true },
                    )
                },
        ) {
            AsyncImage(
                model = urls[index].toSecureUrl(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(urls.size) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            // Panning is only meaningful once zoomed in.
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    },
            )

            // Close.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x66000000))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (urls.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StepButton(
                        label = "上一张",
                        enabled = index > 0,
                        onClick = { index -= 1 },
                    )
                    Text(
                        text = "${index + 1} / ${urls.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    StepButton(
                        label = "下一张",
                        enabled = index < urls.lastIndex,
                        onClick = { index += 1 },
                    )
                }
            }

            if (menuOpen) {
                // The long-press menu. Two actions, so a bar reads better than a
                // floating list anchored to an unknown touch point.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xE6101014))
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MenuAction(
                        label = if (busy) "处理中…" else "保存图片",
                        onClick = {
                            // API 26–28 need the permission before MediaStore
                            // will accept the write; 29+ never reaches a dialog.
                            if (needsWritePermission(context)) {
                                permissionLauncher.launch(
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                )
                            } else {
                                withBitmap { saveToGallery(context, it) }
                            }
                        },
                    )
                    MenuAction(
                        label = "分享图片",
                        onClick = { withBitmap { shareBitmap(context, it) } },
                    )
                }
            }

            if (busy) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) Color.White else Color(0x59FFFFFF),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** True when the write needs a runtime permission (API 26–28 only). */
private fun needsWritePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * The image as a software bitmap.
 *
 * `allowHardware(false)` matters: a hardware bitmap cannot be compressed, so
 * 保存 would throw on exactly the large images somebody would want to keep.
 */
private suspend fun loadBitmap(context: Context, url: String): Bitmap? =
    withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(url.toSecureUrl())
            .allowHardware(false)
            .build()
        (context.imageLoader.execute(request) as? SuccessResult)
            ?.drawable
            ?.toBitmap()
    }

/**
 * Writes the bitmap into the system gallery under `Pictures/YtBili`, the same
 * album the player's screenshots use.
 */
private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
    val name = "YtBili-" + System.currentTimeMillis() + ".png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YtBili")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        return false
    }
    val written = runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }.isSuccess

    Toast.makeText(
        context,
        if (written) "已保存到相册 Pictures/YtBili" else "保存失败",
        Toast.LENGTH_SHORT,
    ).show()
    return written
}

/** Writes the bitmap to the cache and hands it to the system share sheet. */
private fun shareBitmap(context: Context, bitmap: Bitmap) {
    runCatching {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "share-" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }.onFailure {
        Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
    }
}
