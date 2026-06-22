package com.example.complaints.complaint.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.ComplaintProperties;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.model.ComplaintImageType;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.storage.StorageException;
import com.example.complaints.storage.StorageService;
import com.example.complaints.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Validates uploaded multipart parts against the {@code app.complaint.*} limits and persists them
 * via {@link StorageService} + {@link ComplaintImageRepository}.
 *
 * <p>Called by {@link ComplaintCreationService} inside the submit transaction. On any storage
 * write failure mid-batch, the caller's transaction rollback combined with a best-effort
 * cleanup of already-stored keys (see {@link #storeAll}) keeps orphaned blobs out of the
 * bucket. There is exactly one external side-effect in scope (storage) so the
 * "≤ 1 external call per transaction" rule from copilot-instructions is honoured.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintImageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final ComplaintProperties props;
    private final StorageService storage;
    private final ComplaintImageRepository imageRepo;

    /**
     * Validate + store + persist every image in {@code files} for {@code complaintId}.
     * Returns the persisted {@link ComplaintImage} rows in input order.
     *
     * <p>If a storage write fails partway, any already-written keys are deleted on the way out
     * before rethrowing as {@link BusinessException}({@link ErrorCode#IMAGE_UPLOAD_FAILED}) so
     * the surrounding transaction can roll back the DB side cleanly.</p>
     */
    public List<ComplaintImage> storeAll(Long complaintId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        validateBatch(files);

        List<String> writtenKeys = new ArrayList<>(files.size());
        List<ComplaintImage> persisted = new ArrayList<>(files.size());
        try {
            for (MultipartFile file : files) {
                String key = buildKey(complaintId, file.getContentType());
                try {
                    StoredObject stored = storage.store(
                            key, file.getInputStream(), file.getContentType(), file.getSize());
                    writtenKeys.add(stored.key());
                    persisted.add(imageRepo.save(toEntity(complaintId, stored)));
                } catch (IOException e) {
                    throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
                } catch (StorageException e) {
                    log.warn("Storage failure for complaint {}: {}", complaintId, e.getMessage());
                    throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
                }
            }
            return persisted;
        } catch (RuntimeException ex) {
            // Best-effort blob cleanup; DB rollback will handle the rows we may have inserted.
            for (String key : writtenKeys) {
                try {
                    storage.delete(key);
                } catch (StorageException cleanup) {
                    log.warn("Cleanup of {} after submit failure also failed: {}", key, cleanup.getMessage());
                }
            }
            throw ex;
        }
    }

    private void validateBatch(List<MultipartFile> files) {
        if (files.size() > props.maxImages()) {
            throw new BusinessException(ErrorCode.IMAGE_LIMIT_EXCEEDED);
        }
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw new BusinessException(ErrorCode.IMAGE_INVALID_TYPE);
            }
            if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
                throw new BusinessException(ErrorCode.IMAGE_INVALID_TYPE);
            }
            if (file.getSize() > props.maxImageBytes()) {
                throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
            }
        }
    }

    private static String buildKey(Long complaintId, String contentType) {
        String ext = "image/png".equals(contentType) ? "png" : "jpg";
        return String.format(Locale.ROOT, "complaint/%d/COMPLAINT/%s.%s",
                complaintId, UUID.randomUUID(), ext);
    }

    private static ComplaintImage toEntity(Long complaintId, StoredObject stored) {
        return ComplaintImage.builder()
                .complaintId(complaintId)
                .imageType(ComplaintImageType.COMPLAINT)
                .storageKey(stored.key())
                .sizeBytes((int) stored.sizeBytes())
                .contentType(stored.contentType())
                .build();
    }
}

