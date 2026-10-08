package com.znxsgl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.znxsgl.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    /** 加行锁读取课表记录，用于调课等写操作串行化，防止并发覆盖。 */
    @Select("SELECT * FROM schedule WHERE id = #{id} FOR UPDATE")
    Schedule selectByIdForUpdate(@Param("id") Long id);
}