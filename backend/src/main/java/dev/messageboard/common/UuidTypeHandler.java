package dev.messageboard.common;

import org.apache.ibatis.type.*;
import java.sql.*;
import java.util.UUID;

@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override public void setNonNullParameter(PreparedStatement ps,int i,UUID value,JdbcType type) throws SQLException { ps.setObject(i,value,Types.OTHER); }
    @Override public UUID getNullableResult(ResultSet rs,String name) throws SQLException { return rs.getObject(name,UUID.class); }
    @Override public UUID getNullableResult(ResultSet rs,int index) throws SQLException { return rs.getObject(index,UUID.class); }
    @Override public UUID getNullableResult(CallableStatement cs,int index) throws SQLException { return cs.getObject(index,UUID.class); }
}
