package com.example.complaints.auth.mapper;

import com.example.complaints.auth.dto.StaffListItemResponse;
import com.example.complaints.auth.dto.StaffSummaryResponse;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.common.util.DateUtils;
import org.springframework.stereotype.Component;

/**
 * Hand-written entity ↔ DTO mapper (no MapStruct — see {@code copilot-instructions.md} hard rule 3).
 */
@Component
public class UserAccountMapper {

    public StaffSummaryResponse toSummary(UserAccount u) {
        return new StaffSummaryResponse(
                u.getId(),
                u.getEmployeeId(),
                u.getFullName(),
                u.getRole(),
                u.getSubdivisionId(),
                u.getDistributionCenterId(),
                u.isPasswordResetRequired(),
                u.isNotificationsPushEnabled()
        );
    }

    public StaffListItemResponse toListItem(UserAccount u) {
        return new StaffListItemResponse(
                u.getId(),
                u.getEmployeeId(),
                u.getFullName(),
                u.getRole(),
                u.getEmail(),
                u.getMobile(),
                u.getSubdivisionId(),
                u.getDistributionCenterId(),
                u.isEnabled(),
                u.isPasswordResetRequired(),
                DateUtils.toIst(u.getLastLoginAt()),
                DateUtils.toIst(u.getCreatedAt()),
                DateUtils.toIst(u.getUpdatedAt())
        );
    }
}

