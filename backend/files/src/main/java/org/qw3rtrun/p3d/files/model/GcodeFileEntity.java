package org.qw3rtrun.p3d.files.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.SliceInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row about a stored file. The bytes are not here and never will be.
 *
 * <p>What is kept is what can be answered without opening the file: the hash that identifies it,
 * its size and line count, and whatever the slicer said. Everything on this row was computed once,
 * while the upload streamed past, and none of it changes afterwards - a stored file is immutable,
 * so there is no operation on this entity that edits anything.
 */
@Entity
@Table(name = "gcode_file")
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

    /** This row as the record that leaves the module. An entity never does. */
    public GcodeFile describe() {
        return new GcodeFile(id, name, contentHash, sizeBytes, lineCount, uploadedAt,
                new SliceInfo(slicer, estimatedSeconds, filamentMillimetres, layerHeight));
    }
}
