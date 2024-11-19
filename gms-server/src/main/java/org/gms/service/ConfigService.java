package org.gms.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gms.config.GameConfig;
import org.gms.constants.string.CharsetConstants;
import org.gms.dao.entity.GameConfigDO;
import org.gms.dao.mapper.GameConfigMapper;
import org.gms.exception.BizException;
import org.gms.model.dto.ConfigTypeDTO;
import org.gms.model.dto.GameConfigReqDTO;
import org.gms.net.server.Server;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;
import org.gms.util.RequireUtil;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.mybatisflex.core.query.QueryMethods.distinct;
import static org.gms.dao.entity.table.GameConfigDOTableDef.GAME_CONFIG_D_O;

@Service
@AllArgsConstructor
@Slf4j
public class ConfigService {
    private final GameConfigMapper gameConfigMapper;

    public List<GameConfigDO> loadGameConfigs() {
        return gameConfigMapper.selectAll();
    }

    public ConfigTypeDTO getConfigTypeList() {
        List<GameConfigDO> typeDOList = gameConfigMapper.selectListByQuery(QueryWrapper.create().select(distinct(GAME_CONFIG_D_O.CONFIG_TYPE)));
        List<GameConfigDO> subTypeDOList = gameConfigMapper.selectListByQuery(QueryWrapper.create().select(distinct(GAME_CONFIG_D_O.CONFIG_SUB_TYPE)));
        return ConfigTypeDTO.builder()
                .types(typeDOList.stream().map(GameConfigDO::getConfigType).toList())
                .subTypes(subTypeDOList.stream().map(GameConfigDO::getConfigSubType).toList())
                .build();
    }

    public Page<GameConfigDO> getConfigList(GameConfigReqDTO condition) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (!RequireUtil.isEmpty(condition.getType()))
            queryWrapper.and(GAME_CONFIG_D_O.CONFIG_TYPE.eq(condition.getType()));
        if (!RequireUtil.isEmpty(condition.getSubType()))
            queryWrapper.and(GAME_CONFIG_D_O.CONFIG_SUB_TYPE.eq(condition.getSubType()));
        if (!RequireUtil.isEmpty(condition.getFilter())) {
            queryWrapper.and(GAME_CONFIG_D_O.CONFIG_CODE.like(condition.getFilter()).or(GAME_CONFIG_D_O.CONFIG_DESC.like(condition.getFilter())));
        }

        Page<GameConfigDO> page = gameConfigMapper.paginate(condition.getPageNo(), condition.getPageSize(), queryWrapper);
        page.getRecords().forEach(record -> {
            if (record.getConfigDesc() == null) {
                return;
            }
            int start = record.getConfigDesc().indexOf("(");
            int end = record.getConfigDesc().indexOf(")");
            if (start == -1 || end == -1) {
                return;
            }
            String desc;
            if (CharsetConstants.isZhCN()) {
                desc = record.getConfigDesc().substring(0, start);
            } else {
                desc = record.getConfigDesc().substring(start + 1, end);
            }
            record.setConfigDesc(desc);
        });
        return page;
    }

    @Transactional(rollbackFor = Exception.class)
    public void addConfig(GameConfigDO condition) {
        RequireUtil.requireNotEmpty(condition.getConfigType(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configType"));
        RequireUtil.requireNotEmpty(condition.getConfigSubType(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configSubType"));
        RequireUtil.requireNotEmpty(condition.getConfigCode(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configCode"));
        RequireUtil.requireNotEmpty(condition.getConfigValue(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configValue"));
        List<GameConfigDO> gameConfigDOList = gameConfigMapper.selectListByQuery(QueryWrapper.create()
                .where(GAME_CONFIG_D_O.CONFIG_TYPE.eq(condition.getConfigType()))
                .where(GAME_CONFIG_D_O.CONFIG_SUB_TYPE.eq(condition.getConfigSubType()))
                .and(GAME_CONFIG_D_O.CONFIG_CODE.eq(condition.getConfigCode())));
        RequireUtil.requireTrue(gameConfigDOList.isEmpty(), I18nUtil.getExceptionMessage("ConfigService.addConfig.exception1"));
        condition.setId(null);
        gameConfigMapper.insertSelective(condition);
        GameConfig.add(condition);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(GameConfigDO condition) {
        RequireUtil.requireNotNull(condition.getId(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_NULL", "id"));
        RequireUtil.requireNotEmpty(condition.getConfigValue(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configValue"));
        gameConfigMapper.update(GameConfigDO.builder()
                .id(condition.getId())
                .configValue(condition.getConfigValue())
                .configDesc(condition.getConfigDesc())
                .build());
        GameConfigDO gameConfigDO = gameConfigMapper.selectOneById(condition.getId());
        GameConfig.update(gameConfigDO);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        RequireUtil.requireNotNull(id, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_NULL", "id"));
        GameConfigDO gameConfigDO = gameConfigMapper.selectOneById(id);
        gameConfigMapper.deleteById(id);
        GameConfig.remove(gameConfigDO);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteConfigList(List<Long> ids) {
        RequireUtil.requireNotEmpty(ids, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "ids"));
        List<GameConfigDO> gameConfigDOS = gameConfigMapper.selectListByIds(ids);
        gameConfigMapper.deleteBatchByIds(ids);
        gameConfigDOS.forEach(GameConfig::remove);
    }

    public int importYml(MultipartFile file) {
        String filename = file.getOriginalFilename();
        RequireUtil.requireNotEmpty(filename, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "file"));
        RequireUtil.requireTrue(filename.endsWith(".yml") || filename.endsWith(".yaml"), I18nUtil.getExceptionMessage("UNSUPPORTED_TYPE") + ": " + filename);
        try {
            Yaml yaml = new Yaml();
            LinkedHashMap<String, Object> property = yaml.load(file.getInputStream());
            JSONObject gmsProperty = JSONObject.parse(JSONObject.toJSONString(property.get("gms")));
            JSONArray worlds = gmsProperty.getJSONObject("world").getJSONArray("worlds");
            JSONObject server = gmsProperty.getJSONObject("server");

            StringBuilder updateSql = new StringBuilder();
            for (int i = 0; i < worlds.size(); i++) {
                JSONObject world = worlds.getJSONObject(i);
                if (world.getFloat("exp_rate") == null) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : world.entrySet()) {
                    String configCode = entry.getKey().toLowerCase();
                    configCode = replaceWithEquals(configCode, new String[]{"why_am_i_recommended", "recommend_message"},
                            new String[]{"channels", "channel_size"});
                    updateSql.append("update game_config set config_value = '").append(entry.getValue())
                            .append("' where config_type = 'world' and config_sub_type = '").append(i)
                            .append("' and config_code = '").append(configCode).append("';\n");
                }
            }
            for (Map.Entry<String, Object> entry : server.entrySet()) {
                String configCode = entry.getKey().toLowerCase();
                configCode = replaceWithEquals(configCode, new String[]{"wldlist_size", "max_world_size"},
                        new String[]{"channel_size", "max_channel_size"}, new String[]{"channel_load", "channel_capacity"},
                        new String[]{"host", "wan_host"}, new String[]{"lanhost", "lan_host"},
                        new String[]{"use_debug_show_rcvd_mvlife", "use_debug_show_life_move"});
                configCode = replaceWithContains(configCode, new String[]{"use_maxrange", "use_max_range"},
                        new String[]{"charslot", "chr_slot"}, new String[]{"multiclient", "multi_client"},
                        new String[]{"keyset", "key_set"}, new String[]{"eqpexp", "eqp_exp"},
                        new String[]{"autoassign", "auto_assign"}, new String[]{"autoban", "auto_ban"},
                        new String[]{"openshop", "open_shop"}, new String[]{"shopitemsold", "shop_item_sold"},
                        new String[]{"cashshop", "cash_shop"}, new String[]{"atkup", "atk_up"},
                        new String[]{"unitprice", "unit_price"}, new String[]{"buffstat", "buff_stat"},
                        new String[]{"autoaggro", "auto_aggro"}, new String[]{"chscroll", "chaos_scroll"},
                        new String[]{"skillset", "skill_set"}, new String[]{"equipmnt", "equipment"},
                        new String[]{"lvlup", "level_up"}, new String[]{"levelup", "level_up"},
                        new String[]{"extraheal", "extra_heal"}, new String[]{"autopot", "auto_pot"},
                        new String[]{"autohp", "auto_hp"}, new String[]{"automp", "auto_mp"});

                Object configValue = entry.getValue();
                if ("npcs_scriptable".equalsIgnoreCase(entry.getKey())) {
                    configValue = JSONObject.toJSONString(entry.getValue());
                }
                updateSql.append("update game_config set config_value = '").append(configValue)
                        .append("' where config_type = 'server' and config_code = '").append(configCode).append("';\n");
            }
            String[] updateArr = updateSql.toString().split("\n");
            for (String str : updateArr) {
                try (Connection connection = DatabaseConnection.getConnection();
                     PreparedStatement statement = connection.prepareStatement(str)) {
                    statement.executeUpdate();
                }
            }

        } catch (Exception e) {
            String msg = I18nUtil.getExceptionMessage("FILE_PARSE_ERROR");
            log.error(msg, e);
            throw new BizException(msg);
        }
        // 异步重启，这里千万不要用ThreadManager，因为停止服务会注销所有线程
        Thread.startVirtualThread(Server.getInstance().shutdown(true));
        // 返回成功的数量
        return 1;
    }

    private String replaceWithEquals(String src, String[]... fts) {
        for (String[] ft : fts) {
            if (ft[0].equals(src)) {
                src = ft[1];
                break;
            }
        }
        return src;
    }

    private String replaceWithContains(String src, String[]... fts) {
        for (String[] ft : fts) {
            if (src.contains(ft[0])) {
                src = src.replace(ft[0], ft[1]);
            }
        }
        return src;
    }

    public ResponseEntity<Resource> exportYml() {
        List<GameConfigDO> gameConfigDOS = loadGameConfigs();
        // 转成yml格式
        Map<String, List<GameConfigDO>> worldCollect = gameConfigDOS.stream().filter(config -> "world".equals(config.getConfigType()))
                .collect(Collectors.groupingBy(GameConfigDO::getConfigSubType));
        List<Map<String, Object>> worldList = new ArrayList<>();
        for (Map.Entry<String, List<GameConfigDO>> entry : worldCollect.entrySet()) {
            worldList.add(entry.getValue().stream().collect(toMap()));
        }
        Map<String, Object> worlds = new HashMap<>();
        worlds.put("worlds", worldList);

        Map<String, Object> serverCollect = gameConfigDOS.stream().filter(config -> "server".equals(config.getConfigType()))
                .collect(toMap());

        Map<String, Object> gms = new HashMap<>();
        gms.put("world", worlds);
        gms.put("server", serverCollect);

        Map<String, Object> ymlCollect = new HashMap<>();
        ymlCollect.put("gms", gms);

        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
            Yaml yaml = new Yaml(options);
            StringWriter writer = new StringWriter();
            yaml.dump(ymlCollect, writer);
            byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
            Resource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "export.yml";
                }
            };
            HttpHeaders headers = new HttpHeaders();
            // 需要将CONTENT_DISPOSITION暴露给前端，否则前端识别不到头信息的CONTENT_DISPOSITION
            headers.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"");
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (Exception e) {
            String msg = I18nUtil.getExceptionMessage("FILE_CREATE_ERROR");
            log.error(msg, e);
            throw new BizException(msg);
        }

    }

    public Collector<GameConfigDO, ?, Map<String, Object>> toMap() {
        return Collectors.toMap(GameConfigDO::getConfigCode, config -> {
            if ("java.util.Map".equals(config.getConfigClazz())) {
                return JSONObject.parseObject(config.getConfigValue(), new TypeReference<Map<Integer, Object>>() {
                });
            } else {
                try {
                    return JSONObject.parseObject(config.getConfigValue(), Class.forName(config.getConfigClazz()));
                } catch (Exception e) {
                    return config.getConfigValue();
                }
            }
        });
    }
}