package com.qinghuan.workbench.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
        @NotBlank @Size(max = 50) String displayName,
        @NotNull Long roleId,
        @NotNull Boolean enabled) {
}
