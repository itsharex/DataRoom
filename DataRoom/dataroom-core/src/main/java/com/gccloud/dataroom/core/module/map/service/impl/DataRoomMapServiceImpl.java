package com.gccloud.dataroom.core.module.map.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gccloud.common.exception.GlobalException;
import com.gccloud.common.utils.BeanConvertUtils;
import com.gccloud.common.utils.JSON;
import com.gccloud.dataroom.core.module.map.dto.DataRoomMapRepeatDTO;
import com.gccloud.dataroom.core.module.map.vo.DataRoomMapVO;
import com.gccloud.dataroom.core.module.map.vo.MapChildVO;
import com.gccloud.dataroom.core.module.map.dao.DataRoomMapDao;
import com.gccloud.dataroom.core.module.map.dto.DataRoomMapDTO;
import com.gccloud.dataroom.core.module.map.dto.MapSearchDTO;
import com.gccloud.dataroom.core.module.map.entity.DataRoomMapEntity;
import com.gccloud.dataroom.core.module.map.service.IDataRoomMapService;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author hongyang
 * @version 1.0
 * @date 2023/8/31 14:08
 */
@Service
public class DataRoomMapServiceImpl extends ServiceImpl<DataRoomMapDao, DataRoomMapEntity> implements IDataRoomMapService {

    public static final int YES = 1;
    public static final int NO = 0;

    @Override
    public List<DataRoomMapVO> getList(MapSearchDTO searchDTO) {
        return this.baseMapper.getList(searchDTO);
    }

    @Override
    public List<DataRoomMapVO> getAvailableTree(Integer level) {
        // 根据层级，如果某个地图的某个子级（或子级的子级...）也符合该层级，那么把该地图也返回
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(DataRoomMapEntity::getId, DataRoomMapEntity::getLevel, DataRoomMapEntity::getParentId,
                DataRoomMapEntity::getMapCode, DataRoomMapEntity::getName, DataRoomMapEntity::getUploadedGeoJson);
        queryWrapper.le(DataRoomMapEntity::getLevel, level);
        List<DataRoomMapEntity> list = list(queryWrapper);
        // 转成树形结构
        return this.convertToTree(list, level);
    }


    /**
     * 转树形结构
     * @param list
     * @return
     */
    private List<DataRoomMapVO> convertToTree(List<DataRoomMapEntity> list, Integer targetLevel) {
        List<DataRoomMapVO> voList = BeanConvertUtils.convert(list, DataRoomMapVO.class);
        // 根节点
        List<DataRoomMapVO> rootList = Lists.newArrayList();
        // 组装id为key，地图为value的map
        Map<String, DataRoomMapVO> map = new HashMap<>();
        voList.forEach(vo -> map.put(vo.getId(), vo));
        // 目标层级的地图
        List<DataRoomMapVO> targetLevelList = voList.stream().filter(vo -> vo.getLevel().equals(targetLevel)).collect(Collectors.toList());
        // 目标层级的地图，以及其父级地图...
        List<DataRoomMapVO> match = Lists.newArrayList(targetLevelList);
        for (DataRoomMapVO mapVO : targetLevelList) {
            mapVO.setDisabled(!mapVO.getUploadedGeoJson().equals(YES));
            if (mapVO.getLevel().equals(0)) {
                // 已经是最顶级了，没有父级了
                continue;
            }
            DataRoomMapVO parent = map.get(mapVO.getParentId());
            if(parent == null) {
                continue;
            }
            // 将目标层级的地图，以及其父级地图...加入到match中
            this.getParentMap(parent, map, match);
        }
        for (DataRoomMapVO vo : match) {
            if (vo.getParentId().equals(SUPER_PARENT_ID)) {
                rootList.add(vo);
                continue;
            }
            DataRoomMapVO parent = map.get(vo.getParentId());
            if (parent == null) {
                continue;
            }
            if (parent.getChildren() == null) {
                parent.setChildren(Lists.newArrayList());
            }
            parent.getChildren().add(vo);
        }
        return rootList;
    }

    /**
     * 获取地图的父级地图，以及父级地图的父级地图...
     * @param parentMap
     * @param mapIdMap
     * @param match
     */
    private void getParentMap(DataRoomMapVO parentMap, Map<String, DataRoomMapVO> mapIdMap, List<DataRoomMapVO> match) {
        if (parentMap == null) {
            return;
        }
        if (match.contains(parentMap)) {
            // 已经包含了，不需要再加入了
            return;
        }
        match.add(parentMap);
        if (parentMap.getLevel().equals(0)) {
            // 已经是最顶级了，没有父级了
            return;
        }
        DataRoomMapVO parent = mapIdMap.get(parentMap.getParentId());
        this.getParentMap(parent, mapIdMap, match);
    }




    @Override
    public String add(DataRoomMapDTO mapDTO) {
        if (StringUtils.isBlank(mapDTO.getParentId())) {
            mapDTO.setParentId(SUPER_PARENT_ID);
        }
        if (StringUtils.isBlank(mapDTO.getMapCode())) {
            throw new GlobalException("地图编码不能为空");
        }
        DataRoomMapEntity mapEntity = BeanConvertUtils.convert(mapDTO, DataRoomMapEntity.class);
        if (StringUtils.isNotBlank(mapEntity.getGeoJson())) {
            mapEntity.setUploadedGeoJson(YES);
        } else {
            mapEntity.setUploadedGeoJson(NO);
        }
        this.save(mapEntity);
        if (mapDTO.getAutoParseNextLevel().equals(YES) && mapEntity.getUploadedGeoJson().equals(YES)) {
            // 自动从geoJson中解析下一级的基础数据，保存到数据库，将geoJson上传状态置为false
            this.parseNextLevelAndSave(mapEntity, mapEntity.getGeoJson());
        }
        return mapEntity.getId();
    }

    @Override
    public void update(DataRoomMapDTO mapDTO) {
        if (StringUtils.isBlank(mapDTO.getId())) {
            throw new GlobalException("地图id不能为空");
        }
        DataRoomMapEntity old = this.getById(mapDTO.getId());
        if (old == null) {
            throw new GlobalException("地图不存在");
        }
        Integer uploadedGeoJson = old.getUploadedGeoJson();
        LambdaUpdateWrapper<DataRoomMapEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(DataRoomMapEntity::getId, mapDTO.getId());
        // 修改名称
        updateWrapper.set(DataRoomMapEntity::getName, mapDTO.getName());
        // 修改地图编码
        updateWrapper.set(!old.getMapCode().equals(mapDTO.getMapCode()), DataRoomMapEntity::getMapCode, mapDTO.getMapCode());
        // 如果之前没有上传过geoJson，现在上传了，那么允许更新geoJson
        if (!uploadedGeoJson.equals(YES) && StringUtils.isNotBlank(mapDTO.getGeoJson())) {
            if (mapDTO.getAutoParseNextLevel().equals(YES)) {
                // 自动从geoJson中解析下一级的基础数据，保存到数据库
                this.parseNextLevelAndSave(old, mapDTO.getGeoJson());
            }
            updateWrapper.set(DataRoomMapEntity::getGeoJson, mapDTO.getGeoJson());
            updateWrapper.set(DataRoomMapEntity::getUploadedGeoJson, YES);
        }
        this.update(updateWrapper);
        // 更新父级的geoJson
        if (!old.getMapCode().equals(mapDTO.getMapCode())) {
            this.updateParentJson(old.getParentId(), old.getMapCode(), mapDTO.getMapCode());
        }
    }

    /**
     * 从geoJson中解析下一级的基础数据，保存到数据库
     * @param mapEntity
     * @param geoJson
     */
    private void parseNextLevelAndSave(DataRoomMapEntity mapEntity, String geoJson) {
        JSONObject jsonObject = new JSONObject(geoJson);
        JSONArray features = jsonObject.getJSONArray("features");
        if (features == null || features.length() == 0) {
            throw new GlobalException("GeoJson格式不正确，自动解析失败");
        }
        List<DataRoomMapEntity> mapEntityList = Lists.newArrayList();
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject properties = feature.getJSONObject("properties");
            if (properties == null) {
                continue;
            }
            DataRoomMapEntity childMapEntity = new DataRoomMapEntity();
            childMapEntity.setParentId(mapEntity.getId());
            childMapEntity.setLevel(mapEntity.getLevel() + 1);
            childMapEntity.setMapCode(properties.getString("name"));
            childMapEntity.setName(properties.getString("name"));
            childMapEntity.setUploadedGeoJson(NO);
            mapEntityList.add(childMapEntity);
        }
        if (mapEntityList.size() > 0) {
            this.saveBatch(mapEntityList);
        }
    }

    /**
     * 更新父级地图的geoJson
     * @param parentId
     * @param oldCode
     * @param newMapCode
     */
    private void updateParentJson(String parentId, String oldCode, String newMapCode) {
        if (StringUtils.isBlank(parentId) || parentId.equals(SUPER_PARENT_ID)) {
            return;
        }
        DataRoomMapEntity parent = this.getById(parentId);
        String geoJson = parent.getGeoJson();
        if (StringUtils.isBlank(geoJson)) {
            return;
        }
        JSONObject jsonObject = new JSONObject(geoJson);
        JSONArray features = jsonObject.getJSONArray("features");
        if (features == null || features.length() == 0) {
            return;
        }
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject properties = feature.getJSONObject("properties");
            if (properties == null) {
                continue;
            }
            String name = properties.getString("name");
            if (oldCode.equals(name)) {
                properties.put("name", newMapCode);
                break;
            }
        }
        LambdaUpdateWrapper<DataRoomMapEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(DataRoomMapEntity::getId, parentId);
        updateWrapper.set(DataRoomMapEntity::getGeoJson, jsonObject.toString());
        this.update(updateWrapper);
    }


    @Override
    public void delete(String id) {
        if (StringUtils.isBlank(id)) {
            return;
        }
        DataRoomMapEntity mapEntity = this.getById(id);
        if (mapEntity == null) {
            return;
        }
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DataRoomMapEntity::getParentId, mapEntity.getId());
        List<DataRoomMapEntity> list = this.list(queryWrapper);
        if (list != null && list.size() > 0) {
            throw new GlobalException("该地图下存在子地图，不能删除");
        }
        this.removeById(id);
    }


    /**
     * 级联删除
     * @param id
     */
    @Override
    public void cascadingDelete(String id) {
        if (StringUtils.isBlank(id)) {
            return;
        }
        DataRoomMapEntity mapEntity = this.getById(id);
        if (mapEntity == null) {
            return;
        }
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DataRoomMapEntity::getParentId, mapEntity.getId());
        List<DataRoomMapEntity> list = this.list(queryWrapper);
        if (list != null && list.size() > 0) {
            for (DataRoomMapEntity entity : list) {
                this.cascadingDelete(entity.getId());
            }
        }
        this.removeById(id);
    }

    @Override
    public DataRoomMapEntity info(String id) {
        DataRoomMapEntity mapEntity = getById(id);
        if (mapEntity == null) {
            throw new GlobalException("地图不存在");
        }
        return mapEntity;
    }

    @Override
    public DataRoomMapEntity getByParentIdAndCode(String parentId, String code) {
        if (StringUtils.isBlank(parentId)) {
            parentId = SUPER_PARENT_ID;
        }
        if (StringUtils.isBlank(code)) {
            throw new GlobalException("地图编码不能为空");
        }
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DataRoomMapEntity::getParentId, parentId);
        queryWrapper.eq(DataRoomMapEntity::getMapCode, code);
        List<DataRoomMapEntity> list = this.list(queryWrapper);
        if (list == null || list.size() == 0) {
            return null;
        }
        if (list.size() > 1) {
            throw new GlobalException("地图编码重复");
        }
        return list.get(0);
    }

    @Override
    public DataRoomMapEntity infoByMapCode(String mapCode) {
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DataRoomMapEntity::getMapCode, mapCode);
        List<DataRoomMapEntity> list = this.list(queryWrapper);
        if (list == null || list.size() == 0) {
            throw new GlobalException("地图不存在");
        }
        if (list.size() > 1) {
            throw new GlobalException("地图编码重复");
        }
        return list.get(0);
    }


    /**
     * 根据编码获取地图信息，取到geoJson，根据geoJson解析下一级的基础数据
     * @param id
     * @return
     */
    @Override
    public List<MapChildVO> getChildFromGeo(String id) {
        DataRoomMapEntity mapEntity = this.getById(id);
        if (mapEntity.getUploadedGeoJson().equals(NO)) {
            return Lists.newArrayList();
        }
        String geoJson = mapEntity.getGeoJson();
        if (StringUtils.isBlank(geoJson)) {
            return Lists.newArrayList();
        }
        JSONObject geoObj = JSON.parseObject(geoJson);
        JSONArray features = geoObj.getJSONArray("features");
        if (features == null || features.length() == 0) {
            return Lists.newArrayList();
        }
        // 查询当前地图下的所有子地图
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(DataRoomMapEntity::getMapCode);
        queryWrapper.eq(DataRoomMapEntity::getParentId, id);
        List<DataRoomMapEntity> list = this.list(queryWrapper);
        List<String> mapCodeList = list.stream().map(DataRoomMapEntity::getMapCode).collect(Collectors.toList());
        // 解析geoJson，获取下一级的基础数据
        List<MapChildVO> childList = Lists.newArrayList();
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject properties = feature.getJSONObject("properties");
            if (!properties.has("name")) {
                continue;
            }
            String name = properties.getString("name");
            MapChildVO childVO = new MapChildVO();
            childVO.setName(name);
            childVO.setExist(mapCodeList.contains(name));
            childList.add(childVO);
        }
        return childList;
    }


    @Override
    public void uploadGeoJson(String id, String geoJson) {
        if (StringUtils.isBlank(id)) {
            throw new GlobalException("地图id不能为空");
        }
        if (StringUtils.isBlank(geoJson)) {
            throw new GlobalException("geoJson不能为空");
        }
        DataRoomMapEntity mapEntity = this.getById(id);
        if (mapEntity == null) {
            throw new GlobalException("地图不存在");
        }
        if (mapEntity.getUploadedGeoJson().equals(YES)) {
            throw new GlobalException("该地图已上传过geoJson，不能重复上传");
        }
        mapEntity.setGeoJson(geoJson);
        mapEntity.setUploadedGeoJson(YES);
        this.updateById(mapEntity);
    }

    @Override
    public boolean repeatCheck(DataRoomMapRepeatDTO mapDTO) {
        if (StringUtils.isBlank(mapDTO.getMapCode())) {
            throw new GlobalException("地图编码不能为空");
        }
        if (StringUtils.isBlank(mapDTO.getParentId())) {
            throw new GlobalException("上级地图编码不能为空");
        }
        LambdaQueryWrapper<DataRoomMapEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DataRoomMapEntity::getMapCode, mapDTO.getMapCode());
        queryWrapper.eq(DataRoomMapEntity::getParentId, mapDTO.getParentId());
        if (StringUtils.isNotBlank(mapDTO.getId())) {
            queryWrapper.ne(DataRoomMapEntity::getId, mapDTO.getId());
        }
        List<DataRoomMapEntity> list = this.list(queryWrapper);
        return list != null && list.size() > 0;
    }
}