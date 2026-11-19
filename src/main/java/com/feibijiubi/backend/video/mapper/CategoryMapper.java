package com.feibijiubi.backend.video.mapper;

import com.feibijiubi.backend.video.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CategoryMapper {
    List<Category> categoryList();

    /** 按主分区 + 子分区精确读取一条分类；用于导入等需要服务端校验二级分类组合的写路径。 */
    Category selectByMcIdAndScId(@Param("mcId") String mcId, @Param("scId") String scId);
}
