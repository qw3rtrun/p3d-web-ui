package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.g.code.core.block.GBlockReader;
import org.qw3rtrun.p3d.g.code.core.block.GMeaninglessBlock;
import org.qw3rtrun.p3d.g.code.core.token.GComment;
import org.qw3rtrun.p3d.g.code.core.token.GLineBreak;
import org.qw3rtrun.p3d.g.code.core.token.GToken;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stored G-code file as the blocks a printer should be sent, read lazily and without holding it.
 *
 * <p>Framing bytes into lines must not accumulate: a chunk off the file store is an arbitrary slice
 * (a line routinely spans two, one routinely holds thousands), so the remainder carries across
 * chunks rather than building a list - plan item C1 kept a two-hundred-megabyte file off the heap,
 * and reading it back into lines here would undo that in one operator.
 *
 * <p>A block is bare: no {@code N} field, {@code *} checksum, terminator or trailing comment (a
 * comment runs to end of line and would swallow the checksum {@code GEncoder.frame} appends after
 * it) - and a sliced file is full of them, so this is the normal case, not a corner one. The reading
 * itself is {@code :gcode}'s ({@code GBlockReader}), not this class's, same as everywhere else in
 * the repository; a regex over {@code ;} would be faster and wrong on {@code (a note ; not a comment)}.
 *
 * <p>Tokenizing every line costs nothing that matters here: the feed is gated by the printer's
 * command buffer (four in flight, a few hundred a second at best), so the file is read at the speed
 * a machine consumes it. What this path bounds is memory, not throughput.
 */
final class GcodeLines {

    private GcodeLines() {
    }

    /**
     * One block on its way to a machine, and what the file said around it.
     *
     * @param block the bare command, ready for the transport
     * @param layer the layer the file last declared, or null if it never has
     */
    record Fed(String block, Integer layer) {
    }

    /**
     * The blocks worth transmitting, in file order.
     *
     * <p>Blank lines and comment-only lines are dropped rather than sent. Marlin would accept them,
     * but each would spend a line number and a slot in the printer's buffer to say nothing - and a
     * sliced file is perhaps a tenth comments, so it is a tenth of the session spent on them.
     */
    static Flux<String> blocksOf(Publisher<ByteBuffer> content) {
        return feedOf(content).map(Fed::block);
    }

    /**
     * The same, carrying the layer the file was last on - plan item C4's telemetry. Read from the
     * comments this otherwise throws away, the only place it exists: {@code ;LAYER:12} (Cura) or
     * {@code ;LAYER_CHANGE}/{@code ;Z:0.4} (PrusaSlicer); nothing in the G-code itself says what
     * layer a move belongs to. A slicer that announces nothing simply never reports one.
     */
    static Flux<Fed> feedOf(Publisher<ByteBuffer> content) {
        var layer = new AtomicReference<Integer>();
        return lines(content)
                .handle((line, sink) -> {
                    var declared = layerOf(line);
                    if (declared != null) {
                        layer.set(declared);
                    }
                    var block = blockOf(line);
                    if (!block.isEmpty()) {
                        sink.next(new Fed(block, layer.get()));
                    }
                });
    }

    /** The layer a comment declares, or null - read off the comment text, not the raw line, so a {@code ;} inside a parenthesised comment cannot produce one. */
    static Integer layerOf(String line) {
        var blocks = GBlockReader.Companion.blocks(line + "\n").iterator();
        if (!blocks.hasNext()) {
            return null;
        }
        for (GToken token : blocks.next().getRaw()) {
            if (!(token instanceof GComment comment)) {
                continue;
            }
            var text = comment.getString().trim();
            int colon = text.indexOf(':');
            if (colon < 0 || !text.substring(0, colon).trim().equalsIgnoreCase("LAYER")) {
                continue;
            }
            try {
                return Integer.valueOf(text.substring(colon + 1).trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    /**
     * Chunks in, lines out, with the remainder carried across. Hand-written rather than a
     * {@code bufferUntil} over characters - the shape {@code ReplyFlux} uses and the transport's
     * skill names as the one not to copy, since it boxes every byte into a {@code String}; here
     * that would be two hundred million of them. The tail is flushed on completion via
     * {@code concatWith}/{@code defer}, since a file whose last line has no terminator still ends
     * in a line.
     */
    private static Flux<String> lines(Publisher<ByteBuffer> content) {
        var remainder = new StringBuilder();
        return Flux.from(content)
                .concatMap(chunk -> Flux.fromIterable(split(remainder, chunk)), 1)
                .concatWith(Flux.defer(() -> {
                    if (remainder.isEmpty()) {
                        return Flux.empty();
                    }
                    var tail = remainder.toString();
                    remainder.setLength(0);
                    return Flux.just(tail);
                }));
    }

    /**
     * The complete lines in one chunk, leaving any partial one in {@code remainder}.
     *
     * <p>{@code \r} is dropped wherever it falls rather than only at a line end: a file written on
     * Windows carries one before every terminator, and a block that reached the transport with it
     * still attached would be framed into the checksum and rejected by the firmware.
     */
    private static List<String> split(StringBuilder remainder, ByteBuffer chunk) {
        var lines = new ArrayList<String>();
        var decoded = StandardCharsets.UTF_8.decode(chunk.duplicate());
        while (decoded.hasRemaining()) {
            char c = decoded.get();
            if (c == '\n') {
                lines.add(remainder.toString());
                remainder.setLength(0);
            } else if (c != '\r') {
                remainder.append(c);
            }
        }
        return lines;
    }

    /**
     * What one line asks the printer to do, or an empty string when it asks nothing.
     *
     * <p>The tokens of the block's body, minus its comments and its terminator. Taking the body
     * rather than the raw line is what strips an {@code N} field and a {@code *} checksum off a
     * file that happens to carry them - {@code GFramedBlock.body} is defined as the slice between
     * the two - so a file saved from a serial capture feeds as cleanly as one from a slicer.
     */
    static String blockOf(String line) {
        var blocks = GBlockReader.Companion.blocks(line + "\n").iterator();
        if (!blocks.hasNext()) {
            return "";
        }
        var block = blocks.next();
        if (block instanceof GMeaninglessBlock) {
            return "";
        }
        var text = new StringBuilder();
        for (GToken token : block.getBody()) {
            if (token instanceof GComment || token instanceof GLineBreak) {
                continue;
            }
            text.append(token.rawText());
        }
        return text.toString().trim();
    }
}
