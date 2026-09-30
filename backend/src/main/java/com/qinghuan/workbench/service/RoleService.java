package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.role.PermissionItem;
import com.qinghuan.workbench.dto.role.RoleOption;
import com.qinghuan.workbench.dto.role.RoleRequest;
import com.qinghuan.workbench.dto.role.RoleResponse;
import com.qinghuan.workbench.entity.SysPermission;
import com.qinghuan.workbench.entity.SysRole;
import com.qinghuan.workbench.entity.SysUser;
import com.qinghuan.workbench.mapper.SysPermissionMapper;
import com.qinghuan.workbench.mapper.SysRoleMapper;
import com.qinghuan.workbench.mapper.SysRolePermissionMapper;
import com.qinghuan.workbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {
    private static final Map<String, String> REQUIRES = Map.of(
            "task:self:manage", "task:self:view",
            "task:manage:all", "task:view:all");

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserMapper userMapper;

    public List<RoleResponse> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .orderByAsc(SysRole::getId))
                .stream().map(this::toResponse).toList();
    }

    public List<RoleOption> options() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .orderByAsc(SysRole::getName))
                .stream().map(role -> new RoleOption(role.getId(), role.getName()))
                .toList();
    }

    public List<PermissionItem> permissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                        .orderByAsc(SysPermission::getId))
                .stream().map(p -> new PermissionItem(p.getId(), p.getCode(), p.getName(), p.getDescription()))
                .toList();
    }

    public RoleResponse get(Long id) {
        return toResponse(requireRole(id));
    }

    public RoleResponse create(RoleRequest request) {
        String code = request.code().trim();
        if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, code)) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "角色编码已存在");
        }
        List<Long> permissionIds = validatePermissionIds(request.permissionIds());
        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(request.name().trim());
        role.setDescription(request.description());
        role.setBuiltIn(false);
        roleMapper.insert(role);
        if (!permissionIds.isEmpty()) {
            rolePermissionMapper.insertBatch(role.getId(), permissionIds);
        }
        return buildResponse(role, permissionIds);
    }

    public RoleResponse update(Long id, RoleRequest request) {
        SysRole role = requireRole(id);
        String requestedCode = request.code().trim();
        if (Boolean.TRUE.equals(role.getBuiltIn()) && !role.getCode().equals(requestedCode)) {
            throw new BusinessException(HttpStatus.CONFLICT, "内置角色编码不能修改");
        }
        if (!role.getCode().equals(requestedCode)
                && roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getCode, requestedCode)) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "角色编码已存在");
        }

        List<Long> permissionIds = "SUPER_ADMIN".equals(role.getCode())
                ? permissionMapper.selectList(null).stream().map(SysPermission::getId).toList()
                : validatePermissionIds(request.permissionIds());
        role.setCode(requestedCode);
        role.setName(request.name().trim());
        role.setDescription(request.description());
        roleMapper.updateById(role);
        rolePermissionMapper.deleteByRoleId(id);
        if (!permissionIds.isEmpty()) {
            rolePermissionMapper.insertBatch(id, permissionIds);
        }
        return buildResponse(role, permissionIds);
    }

    public void delete(Long id) {
        SysRole role = requireRole(id);
        if ("SUPER_ADMIN".equals(role.getCode())) {
            throw new BusinessException(HttpStatus.CONFLICT, "内置超级管理员角色不能删除");
        }
        long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleId, id));
        if (userCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "角色仍被账号使用，不能删除");
        }
        rolePermissionMapper.deleteByRoleId(id);
        roleMapper.deleteById(id);
    }

    private List<Long> validatePermissionIds(List<Long> rawIds) {
        List<Long> ids = new LinkedHashSet<>(rawIds).stream().toList();
        if (ids.isEmpty()) {
            return ids;
        }
        List<SysPermission> permissions = permissionMapper.selectBatchIds(ids);
        if (permissions.size() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "存在无效权限");
        }
        Set<String> codes = permissions.stream().map(SysPermission::getCode).collect(Collectors.toSet());
        for (Map.Entry<String, String> dependency : REQUIRES.entrySet()) {
            if (codes.contains(dependency.getKey()) && !codes.contains(dependency.getValue())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        dependency.getKey() + " 需要同时选择 " + dependency.getValue());
            }
        }
        return ids;
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private RoleResponse toResponse(SysRole role) {
        List<Long> permissionIds = rolePermissionMapper.selectPermissionIds(role.getId());
        return buildResponse(role, permissionIds);
    }

    private RoleResponse buildResponse(SysRole role, List<Long> permissionIds) {
        Map<Long, String> permissionCodes = permissionIds.isEmpty()
                ? Map.of()
                : permissionMapper.selectBatchIds(permissionIds).stream()
                        .collect(Collectors.toMap(SysPermission::getId, SysPermission::getCode));
        long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleId, role.getId()));
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                Boolean.TRUE.equals(role.getBuiltIn()), userCount, permissionIds,
                permissionIds.stream().map(permissionCodes::get).toList());
    }
}
