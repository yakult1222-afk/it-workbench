package com.qinghuan.workbench.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoleRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$",
                message = "角色编码需以大写字母开头，仅包含大写字母、数字和下划线")
        String code,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 255) String description,
        @NotNull List<@NotNull Long> permissionIds) {
}
