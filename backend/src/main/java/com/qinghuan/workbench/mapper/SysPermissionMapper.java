package com.qinghuan.workbench.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qinghuan.workbench.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
    @Select("""
            SELECT p.* FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.id
            """)
    List<SysPermission> selectByRoleId(Long roleId);
}
