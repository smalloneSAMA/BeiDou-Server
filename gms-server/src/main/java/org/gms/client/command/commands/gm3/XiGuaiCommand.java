package org.gms.client.command.commands.gm3;

import cn.nap.utils.common.NapComUtils;
import org.gms.client.Client;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.command.Command;
import org.gms.client.Character;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.util.Combine;
import org.gms.util.I18nUtil;

import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class XiGuaiCommand extends Command{

    {
        setDescription(I18nUtil.getMessage("XiGuaiCommand.message1"));
    }

    @Override
    public void execute(Client client, String[] params) {
        // 获取玩家信息和地图
        Character player = client.getPlayer();
        MapleMap map = player.getMap();
        List<Combine> combineMsg = map.getXiGuaiCombineMsg();

        if (NapComUtils.isEmpty(combineMsg)) {
            Combine combine = Combine.createFourCombine(player.getId(), player.getClient().getChannel(), map.getId(), player.getPosition());
            combineMsg.add(combine);
            XiGuai(player, map);
            return;
        }

        Optional<Combine> currMapCombineOption = combineMsg.stream().filter(combine -> {
            Integer channel = combine.getSecond();
            Integer mapId = combine.getThird();
            return player.getClient().getChannel() == channel && mapId == map.getId();
        }).findFirst();
        // 如果当前地图没有吸怪
        if (!currMapCombineOption.isPresent()) {
            Combine combine = Combine.createFourCombine(player.getId(), player.getClient().getChannel(), map.getId(), player.getPosition());
            combineMsg.add(combine);
            Integer curPlayerId = combineMsg.getFirst().getFirst();
            XiGuai(player, map);
            return;
        }

        Combine currMapCombine = currMapCombineOption.get();
        Integer playerId = currMapCombine.getFirst();
        if (playerId == player.getId()) {
            // 如果当前玩家已经开启了吸怪，关闭吸怪功能，移除所有记录的地图
            combineMsg.removeIf(combine -> {
                Integer tmpPlayerId = combine.getFirst();
                return tmpPlayerId == player.getId();
            });
            player.message("关闭吸怪");
        } else {
            player.message("当前地图已经有人使用了吸怪");
        }
    }

    //    吸怪
    private void XiGuai(Character player, MapleMap map) {
        // 眩晕技能
        Skill skill = SkillFactory.getSkill(5201004);
        // 眩晕 100%概率 无技能 不施放
        MonsterStatusEffect effect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.STUN, 1), skill, null, false);
        List<Monster> monsterList = map.getAllMonsters();
        for (Monster monster : monsterList) {
            if (monster.isBoss()) {
                continue;
            }
            Point monsterPos = player.getPosition();
            monsterPos.setLocation(monsterPos.x+50, monsterPos.y);
            monster.resetMobPosition(monsterPos);
            // 持续时间1天
            monster.applyStatus(player, effect, false, 86400000, false);
        }
        player.message("开启吸怪");
    }
}
