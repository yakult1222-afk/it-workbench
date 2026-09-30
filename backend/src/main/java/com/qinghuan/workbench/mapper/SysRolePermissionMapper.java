package com.qinghuan.workbench.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper {
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(Long roleId);

    @Insert("""
            <script>
            INSERT INTO sys_role_permission(role_id, permission_id) VALUES
            <foreach collection="permissionIds" item="permissionId" separator=",">
              (#{roleId}, #{permissionId})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("roleId") Long roleId,
                    @Param("permissionIds") List<Long> permissionIds);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIds(Long roleId);
}
