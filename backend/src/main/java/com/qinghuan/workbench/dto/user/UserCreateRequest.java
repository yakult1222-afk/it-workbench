package com.qinghuan.workbench.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码需为 8-64 位且至少包含字母和数字") String password,
        @NotBlank @Size(max = 50) String displayName,
        @NotNull Long roleId,
        Boolean enabled) {
}
