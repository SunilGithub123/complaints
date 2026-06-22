package com.example.complaints.consumer.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.consumer.dto.ConsumerView;
import com.example.complaints.consumer.model.ConsumerMaster;
import com.example.complaints.consumer.repository.ConsumerMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookup API for consumer-master rows. Other modules ({@code auth.OtpService},
 * future {@code complaint.ComplaintCreationService}) depend on this service rather than
 * the repository directly so the {@code consumer} package's repository stays internal
 * (enforced by {@code PackageBoundaryTest}).
 */
@Service
@RequiredArgsConstructor
public class ConsumerLookupService {

    private final ConsumerMasterRepository consumers;

    /**
     * Looks up an active consumer by their external EB consumer number.
     *
     * @throws BusinessException with {@link ErrorCode#CONSUMER_NOT_FOUND} when the consumer ID
     *     does not exist, or {@link ErrorCode#CONSUMER_INACTIVE} when the row is flagged inactive.
     */
    @Transactional(readOnly = true)
    public ConsumerView requireActiveByConsumerId(String consumerId) {
        ConsumerMaster c = consumers.findByConsumerId(consumerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMER_NOT_FOUND));
        if (!c.isActive()) {
            throw new BusinessException(ErrorCode.CONSUMER_INACTIVE);
        }
        return toView(c);
    }

    private static ConsumerView toView(ConsumerMaster c) {
        return new ConsumerView(
                c.getId(),
                c.getConsumerId(),
                c.getName(),
                c.getMobile(),
                c.getDistributionCenterId(),
                c.isActive()
        );
    }
}

