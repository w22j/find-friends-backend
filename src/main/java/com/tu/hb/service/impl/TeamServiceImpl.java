package com.tu.hb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tu.hb.common.ErrorCode;
import com.tu.hb.constant.TeamStatusEnum;
import com.tu.hb.exception.BusinessException;
import com.tu.hb.mapper.TeamMapper;
import com.tu.hb.model.domain.Team;
import com.tu.hb.model.domain.User;
import com.tu.hb.model.domain.UserTeam;
import com.tu.hb.model.dto.TeamQuery;
import com.tu.hb.model.request.TeamDeleteRequest;
import com.tu.hb.model.request.TeamJoinRequest;
import com.tu.hb.model.request.TeamQuitRequest;
import com.tu.hb.model.request.TeamUpdateRequest;
import com.tu.hb.model.vo.TeamUserVO;
import com.tu.hb.model.vo.UserVO;
import com.tu.hb.service.TeamService;
import com.tu.hb.service.UserService;
import com.tu.hb.service.UserTeamService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author The tu
 * @description 针对表【team(队伍表)】的数据库操作Service实现
 * @createDate 2024-01-16 11:06:31
 */
@Service
public class TeamServiceImpl extends ServiceImpl<TeamMapper, Team>
        implements TeamService {

    @Resource
    private UserService userService;

    @Resource
    private UserTeamService userTeamService;

    @Resource
    private RedissonClient redissonClient;

    @Override
    @Transactional
    public Long addTeam(Team team, User loginUser) {

        // 1. 请求参数是否为空
        if (team == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 2. 是否登录，未登录无法创建
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NO_AUTH, "用户未登录");
        }
        // 3. 校验信息
        //  ● 队伍标题不能为空且不能超过20个字
        if (StringUtils.isBlank(team.getName()) || team.getName().length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题不符合要求");
        }
        //  ● 队伍描述可以没有，有的话不能超过512
        if (StringUtils.isNotBlank(team.getDescription()) && team.getDescription().length() > 512) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "描述过长");
        }
        //  ● 人数大于1，小于等于20
        int maxNum = Optional.ofNullable(team.getMaxNum()).orElse(0);
        if (maxNum < 1 || maxNum > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍人数不符合要求");
        }
        //  ● status 是否公开（int）不传默认为 0（公开）
        int status = Optional.ofNullable(team.getStatus()).orElse(0);
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
        if (statusEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍状态不满足要求");
        }
        //  ● 如果 status 是加密状态，一定要有密码，且密码 <= 32
        String password = team.getPassword();
        if (TeamStatusEnum.SECRET.equals(statusEnum)) {
            if (StringUtils.isBlank(password) || password.length() > 32) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码设置不正确");
            }
        }
        //  ● 超时时间>当前时间
        if (team.getExpireTime().before(new Date())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍已过期");
        }
        //  ● 每个用户最多创建5个队伍
        // 有 bug，可能同时创建 100 个队伍
        final Long userId = loginUser.getId();
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", userId);
        long count = this.count(queryWrapper);
        if (count >= 5) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍创建已达上限");
        }
        // 4. 插入队伍信息到队伍表
        //先设置为空，让他自增
        team.setId(null);
        team.setUserId(userId);
        boolean result = this.save(team);
        Long teamId = team.getId();
        if (!result || teamId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建队伍失败");
        }
        // 5. 插入用户、队伍到用户队伍关系表
        UserTeam userTeam = new UserTeam();
        userTeam.setUserId(userId);
        userTeam.setTeamId(teamId);
        userTeam.setJoinTime(new Date());
        result = userTeamService.save(userTeam);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建队伍失败");
        }

        return teamId;
    }

    @Override
    public List<TeamUserVO> listTeams(TeamQuery teamQuery, User loginUser) {
        // 从请求参数中取出队伍名称等查询条件，如果存在则查询
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        // 组合条件查询
        if (teamQuery != null) {
            Long teamId = teamQuery.getId();
            if (teamId != null && teamId > 0) {
                queryWrapper.eq("id", teamId);
            }
            // 根据队伍id列表查询
            List<Long> idList = teamQuery.getIdList();
            if (CollectionUtils.isNotEmpty(idList)) {
                queryWrapper.in("id", idList);
            }
            // 可以通过某个关键词去对名称和描述统一查询
            String searchText = teamQuery.getSearchText();
            if (StringUtils.isNotBlank(searchText)) {
                queryWrapper.and(qw -> qw.like("name", searchText).or().like("description", searchText));
            }
            String name = teamQuery.getName();
            if (StringUtils.isNotBlank(name)) {
                queryWrapper.like("name", name);
            }
            String description = teamQuery.getDescription();
            if (StringUtils.isNotBlank(description)) {
                queryWrapper.like("description", description);
            }
            Integer maxNum = teamQuery.getMaxNum();
            if (maxNum != null && maxNum > 0) {
                queryWrapper.like("maxNum", maxNum);
            }
            Long userId = teamQuery.getUserId();
            if (userId != null && userId > 0) {
                queryWrapper.eq("userId", userId);
            }
            // 只有管理员才能查看私有的队伍
            Integer status = teamQuery.getStatus();
            TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
            if (statusEnum == null) {
                statusEnum = TeamStatusEnum.PUBLIC;
            }
            if (!userService.isAdmin(loginUser) && TeamStatusEnum.PRIVATE.equals(statusEnum)) {
                throw new BusinessException(ErrorCode.NO_AUTH);
            }
            queryWrapper.like("status", statusEnum.getValue());
        }
        // 已过期的队伍不会被查询(查询未过期的)
        // expireTime > new Date or expireTime == null
        queryWrapper.and(qw -> qw.gt("expireTime", new Date()).or().isNull("expireTime"));
        // 根据组合条件查询队伍列表
        List<Team> teamList = this.list(queryWrapper);
        if (CollectionUtils.isEmpty(teamList)) {
            return new ArrayList<>();
        }
        // 关联查询创建人的用户信息
        List<TeamUserVO> teamUserVOList = new ArrayList<>();
        for (Team team : teamList) {
            Long userId = team.getUserId();
            // userId不存在，执行下一次循环插入
            if (userId == null) {
                continue;
            }
            TeamUserVO teamUserVO = new TeamUserVO();
            BeanUtils.copyProperties(team, teamUserVO);
            //脱敏用户信息
            User user = userService.getById(userId);
            if (user != null) {
                UserVO userVO = new UserVO();
                BeanUtils.copyProperties(user, userVO);
                teamUserVO.setCreateUser(userVO);
            }
            teamUserVOList.add(teamUserVO);
        }
        return teamUserVOList;
    }

    /**
     * 获取我加入的队伍
     * @param teamQuery
     * @param loginUser
     * @return
     */
    @Override
    public List<TeamUserVO> listTeamsByJoin(TeamQuery teamQuery, User loginUser) {
        // 从请求参数中取出队伍名称等查询条件，如果存在则查询
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        // 组合条件查询
        if (teamQuery != null) {
            Long teamId = teamQuery.getId();
            if (teamId != null && teamId > 0) {
                queryWrapper.eq("id", teamId);
            }
            // 根据队伍id列表查询
            List<Long> idList = teamQuery.getIdList();
            if (CollectionUtils.isNotEmpty(idList)) {
                queryWrapper.in("id", idList);
            }
            // 可以通过某个关键词去对名称和描述统一查询
            String searchText = teamQuery.getSearchText();
            if (StringUtils.isNotBlank(searchText)) {
                queryWrapper.and(qw -> qw.like("name", searchText).or().like("description", searchText));
            }
            String name = teamQuery.getName();
            if (StringUtils.isNotBlank(name)) {
                queryWrapper.like("name", name);
            }
            String description = teamQuery.getDescription();
            if (StringUtils.isNotBlank(description)) {
                queryWrapper.like("description", description);
            }
            Integer maxNum = teamQuery.getMaxNum();
            if (maxNum != null && maxNum > 0) {
                queryWrapper.like("maxNum", maxNum);
            }
            Long userId = teamQuery.getUserId();
            if (userId != null && userId > 0) {
                queryWrapper.eq("userId", userId);
            }
        }
        // 已过期的队伍不会被查询(查询未过期的)
        // expireTime > new Date or expireTime == null
        queryWrapper.and(qw -> qw.gt("expireTime", new Date()).or().isNull("expireTime"));
        // 根据组合条件查询队伍列表
        List<Team> teamList = this.list(queryWrapper);
        if (CollectionUtils.isEmpty(teamList)) {
            return new ArrayList<>();
        }
        // 关联查询创建人的用户信息
        List<TeamUserVO> teamUserVOList = new ArrayList<>();
        for (Team team : teamList) {
            Long userId = team.getUserId();
            // userId不存在，执行下一次循环插入
            if (userId == null) {
                continue;
            }
            TeamUserVO teamUserVO = new TeamUserVO();
            BeanUtils.copyProperties(team, teamUserVO);
            //脱敏用户信息
            User user = userService.getById(userId);
            if (user != null) {
                UserVO userVO = new UserVO();
                BeanUtils.copyProperties(user, userVO);
                teamUserVO.setCreateUser(userVO);
            }
            teamUserVOList.add(teamUserVO);
        }
        return teamUserVOList;
    }

    @Override
    public boolean updateTeam(TeamUpdateRequest teamUpdateRequest, User loginUser) {

        // 1. 判断请求参数是否为空
        if (teamUpdateRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 2. 队伍是否存在
        Long teamId = teamUpdateRequest.getId();
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 3. 管理员和当前登录用户才能修改队伍
        Team oldTeam = this.getTeamById(teamId);
        if (!userService.isAdmin(loginUser) && !oldTeam.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        // 4. 如果用户传入的新值和老值一致，就不用 update 了（可自行实现，降低数据库使用次数）
        Integer status = teamUpdateRequest.getStatus();
        String password = teamUpdateRequest.getPassword();
        String name = teamUpdateRequest.getName();
        String description = teamUpdateRequest.getDescription();
        Date expireTime = teamUpdateRequest.getExpireTime();
        String avatarUrl = teamUpdateRequest.getAvatarUrl();
        if (oldTeam.getName().equals(name) && oldTeam.getDescription().equals(description) && oldTeam.getStatus().equals(status) && oldTeam.getExpireTime().equals(expireTime) && oldTeam.getAvatarUrl().equals(avatarUrl)) {
            return true;
        }
        // 5. 修改队伍状态为加密时，需要设置密码
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
        if (TeamStatusEnum.SECRET.equals(statusEnum)) {
            if (StringUtils.isBlank(password)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "加密房间时需要设置密码");
            }
        }
        //    修改队伍状态为公开时，清空密码
        if (TeamStatusEnum.PUBLIC.equals(statusEnum)) {
            teamUpdateRequest.setPassword("");
        }
        Team updateTeam = new Team();
        BeanUtils.copyProperties(teamUpdateRequest, updateTeam);
        // 6. 更新成功
        return this.updateById(updateTeam);
    }

    @Override
    public boolean joinTeam(TeamJoinRequest teamJoinRequest, User loginUser) {
        // 1. 队伍未满,未过期且队伍存在时可加入
        if (teamJoinRequest == null ) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long teamId = teamJoinRequest.getTeamId();
        Team team = this.getTeamById(teamId);
        if (team.getExpireTime() != null && team.getExpireTime().before(new Date())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍已过期，无法加入");
        }
        // 2. 不能加入私有的队伍
        Integer status = team.getStatus();
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
        if (TeamStatusEnum.PRIVATE.equals(statusEnum)) {
            throw new BusinessException(ErrorCode.NO_AUTH, "不能加入私有队伍");
        }
        // 3. 队伍状态为加密时，必须密码正确才能加入
        String password = teamJoinRequest.getPassword();
        if (TeamStatusEnum.SECRET.equals(statusEnum)) {
            if (!password.equals(team.getPassword())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码不正确");
            }
        }
        RLock lock = redissonClient.getLock("hb:joinTeam");
        while (true) {
            try {
                if (lock.tryLock(0, -1, TimeUnit.MILLISECONDS)) {
                    System.out.println("getLock: " + Thread.currentThread().getId());
                    //队伍已满
                    QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("teamId", teamId);
                    long count = userTeamService.count(queryWrapper);
                    if (count >= team.getMaxNum()) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍已满");
                    }
                    // 4. 用户最多只能加入5个队伍
                    Long userId = loginUser.getId();
                    queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("userId",userId);
                    long hasJoinNum = userTeamService.count(queryWrapper);
                    if (hasJoinNum >= 5) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户创建和加入队伍已达上线");
                    }
                    // 5. 已加入的队伍不能重复加入
                    queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("teamId", teamId);
                    queryWrapper.eq("userId", userId);
                    long hasUserJoinTeam = userTeamService.count(queryWrapper);
                    if (hasUserJoinTeam > 0) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "已加入队伍");
                    }
                    // 6. 将数据加入到用户队伍关系表
                    UserTeam userTeam = new UserTeam();
                    userTeam.setTeamId(teamId);
                    userTeam.setUserId(userId);
                    userTeam.setJoinTime(new Date());
                    return userTeamService.save(userTeam);
                }
            } catch (InterruptedException e) {
                log.error("doJoin error", e);
                return false;
            } finally {
                // 只能释放自己的锁
                if (lock.isHeldByCurrentThread()) {
                    System.out.println("unLock: " + Thread.currentThread().getId());
                    lock.unlock();
                }
            }
        }

    }

    @Override
    @Transactional
    public boolean quitTeam(TeamQuitRequest teamQuitRequest, User loginUser) {
        // 1. 请求参数是否为空
        if (teamQuitRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 2. 队伍是否存在
        final Long teamId = teamQuitRequest.getTeamId();
        Team team = this.getTeamById(teamId);
        // 3. 校验我是否已经加入队伍
        Long userId = loginUser.getId();
        QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("teamId", teamId);
        queryWrapper.eq("userId", userId);
        long count = userTeamService.count(queryWrapper);
        if (count <= 0) {
            throw new BusinessException(ErrorCode.NULL_ERROR, "用户不在此队伍中");
        }
        // 4. 退出队伍
        //  a. 只剩一人退出，队伍自动解散
        queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("teamId", teamId);
        long hasTeamNumUser = userTeamService.count(queryWrapper);
        if (hasTeamNumUser == 1) {
            this.removeById(teamId);
        } else {
            //  b. 队伍不只一人，队长退出（队长自动转交给最早入队人员）
            if (team.getUserId().equals(userId)) {
                QueryWrapper<UserTeam> userTeamQueryWrapper = new QueryWrapper<>();
                //关系表中id自增 id越大 进入队伍越晚
                userTeamQueryWrapper.eq("teamId", teamId);
                userTeamQueryWrapper.orderByAsc("id");
                userTeamQueryWrapper.last("limit 2");
                List<UserTeam> userTeamList = userTeamService.list(userTeamQueryWrapper);
                if (CollectionUtils.isEmpty(userTeamList) || userTeamList.size() <= 0) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR);
                }
                UserTeam nextTeamUser = userTeamList.get(1);
                Long nextUserId = nextTeamUser.getUserId();
                //更新后的队长
                Team updateTeam = new Team();
                updateTeam.setId(teamId);
                updateTeam.setUserId(nextUserId);
                boolean result = this.updateById(updateTeam);
                if (!result) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新队伍队长失败");
                }

            }
        }
        // 5. 清除关系表数据
        queryWrapper.eq("userId", userId);
        return userTeamService.remove(queryWrapper);
    }

    @Override
    @Transactional
    public boolean deleteTeam(TeamDeleteRequest teamDeleteRequest, User loginUser) {
        if (teamDeleteRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 1. 队伍是否存在
        Long teamId = teamDeleteRequest.getTeamId();
        Team team = getTeamById(teamId);
        // 2. 检验本人是否是队长
        if (!team.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH, "无删除权限");
        }
        // 3. 删除队伍的关系表
        QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("teamId", teamId);
        boolean result = userTeamService.remove(queryWrapper);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除队伍关联信息失败");
        }
        // 4. 直接删除队伍
        return this.removeById(teamId);
    }



    /**
     * 根据id获取队伍信息
     * @param teamId
     * @return
     */
    private Team getTeamById(Long teamId) {
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team team = this.getById(teamId);
        if (team == null) {
            throw new BusinessException(ErrorCode.NULL_ERROR, "队伍不存在");
        }
        return team;
    }
}




