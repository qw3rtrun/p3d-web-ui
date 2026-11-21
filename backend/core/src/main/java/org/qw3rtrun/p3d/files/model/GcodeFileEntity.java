package org.qw3rtrun.p3d.files.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.SliceInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row about a stored file. The bytes are not here and never will be.
 *
 * <p>What is kept is what can be answered without opening the file: the hash that identifies it,
 * its size and line count, and whatever the slicer said. Everything but {@link #shortName} was
 * computed once, while the upload streamed past, and never changes afterwards - a stored file is
 * immutable in that sense, and there is no operation on this entity that edits any of it.
 *
 * <p><strong>{@code shortName} is the one exception, and it is why this entity now carries
 * {@code @DynamicUpdate}</strong> (#148's follow-on, revisiting #100's "neither @DynamicUpdate nor
 * @Version" note). {@link #rememberShortName} is a write {@code store} never makes: it lands on a
 * row the insert already committed, once, the first time a machine without {@code Cap:LFN_WRITE}
 * needs this file. Without {@code @DynamicUpdate} that write would be a full-row {@code UPDATE}
 * naming every column, which costs nothing incorrect here - nothing else is concurrently writing
 * this row - but states the wrong thing in SQL about what the write decides. There is still no
 * {@code @Version}: two machines asking for the same file's short name at once both compute the
 * same deterministic value and the second write is a no-op, not a conflict.
 */
@Entity
@Table(name = "gcode_file")
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class GcodeFileEntity {

    @Id
    UUID id;

    @Column(nullable = false)
    String name;

    /** SHA-256, lower-case hex. Unique: the same bytes are the same file. */
    @Column(name = "content_hash", nullable = false, unique = true, length = 64)
    String contentHash;

    @Column(name = "size_bytes", nullable = false)
    long sizeBytes;

    @Column(name = "line_count", nullable = false)
    long lineCount;

    @Column(name = "uploaded_at", nullable = false)
    LocalDateTime uploadedAt;

    @Column(name = "slice_slicer")
    String slicer;

    @Column(name = "slice_estimated_seconds")
    Long estimatedSeconds;

    @Column(name = "slice_filament_mm")
    BigDecimal filamentMillimetres;

    @Column(name = "slice_layer_height")
    BigDecimal layerHeight;

    /**
     * The DOS-8.3 name a machine without {@code Cap:LFN_WRITE} uses for this file, once one has
     * had to be generated - null until then. Written by {@link #rememberShortName}, the one method
     * on this entity that is not part of the constructor.
     */
    @Column(name = "short_name", length = 12)
    String shortName;

    public GcodeFileEntity(UUID id, String name, String contentHash, long sizeBytes, long lineCount,
                           LocalDateTime uploadedAt, SliceInfo slice) {
        this.id = id;
        this.name = name;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.lineCount = lineCount;
        this.uploadedAt = uploadedAt;
        var read = slice == null ? SliceInfo.unknown() : slice;
        this.slicer = read.slicer();
        this.estimatedSeconds = read.estimatedSeconds();
        this.filamentMillimetres = read.filamentMillimetres();
        this.layerHeight = read.layerHeight();
    }

    /**
     * Records the short name this file will use from now on. Idempotent - the generator is
     * deterministic, so a second machine asking for the same file computes and writes the same
     * value - and it is the only write this entity ever takes outside its constructor.
     */
    public void rememberShortName(String shortName) {
        this.shortName = shortName;
    }

    /** This row as the record that leaves the module. An entity never does. */
    public GcodeFile describe() {
        return new GcodeFile(id, name, contentHash, sizeBytes, lineCount, uploadedAt,
                new SliceInfo(slicer, estimatedSeconds, filamentMillimetres, layerHeight));
    }
}
