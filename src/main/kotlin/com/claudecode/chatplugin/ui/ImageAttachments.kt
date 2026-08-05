package com.claudecode.chatplugin.ui

import com.intellij.openapi.diagnostic.Logger
import java.awt.Image
import java.awt.image.BufferedImage
import java.awt.datatransfer.DataFlavor
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO

/**
 * Turns pasted/chosen images into files on disk that Claude can read.
 *
 * The CLI has no "attach image" flag in headless (`-p`) mode, so an image is
 * handed over the way Claude Code natively consumes one: as a path in the
 * prompt, which Claude opens with its own `Read` tool (Read renders images).
 * Screenshots therefore get written to a temp PNG first.
 */
object ImageAttachments {

    private val log = Logger.getInstance(ImageAttachments::class.java)

    /** True if the system clipboard currently holds an image. */
    fun clipboardHasImage(): Boolean = try {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
    } catch (e: Exception) {
        false
    }

    /**
     * Writes an image to a temp PNG and returns it, or null if there is no
     * readable image.
     *
     * [transferable] is the payload of a paste or drop when one is available;
     * without it the system clipboard is read directly (the toolbar button).
     */
    fun saveClipboardImage(transferable: java.awt.datatransfer.Transferable? = null): File? {
        return try {
            val source = transferable ?: Toolkit.getDefaultToolkit().systemClipboard
                .takeIf { it.isDataFlavorAvailable(DataFlavor.imageFlavor) }
                ?.getContents(null)
                ?: return null
            if (!source.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
            val image = source.getTransferData(DataFlavor.imageFlavor) as? Image ?: return null
            write(toBufferedImage(image))
        } catch (e: Exception) {
            log.warn("Could not read the pasted image", e)
            null
        }
    }

    private fun write(image: BufferedImage): File? {
        val dir = File(System.getProperty("java.io.tmpdir"), "claude-brains-images").apply { mkdirs() }
        val file = File(dir, "screenshot-${System.currentTimeMillis()}.png")
        return if (ImageIO.write(image, "png", file)) file else null
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val buffered = BufferedImage(
            image.getWidth(null).coerceAtLeast(1),
            image.getHeight(null).coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB
        )
        val g = buffered.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return buffered
    }
}
