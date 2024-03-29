package com.tu.hb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tu.hb.common.BaseResponse;
import com.tu.hb.common.ErrorCode;
import com.tu.hb.common.ResultUtils;
import com.tu.hb.exception.BusinessException;
import com.tu.hb.model.domain.Team;
import com.tu.hb.model.domain.User;
import com.tu.hb.model.domain.UserTeam;
import com.tu.hb.model.dto.TeamQuery;
import com.tu.hb.model.request.*;
import com.tu.hb.model.vo.TeamUserVO;
import com.tu.hb.service.TeamService;
import com.tu.hb.service.UserService;
import com.tu.hb.service.UserTeamService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 队伍接口
 */
@RestController
@RequestMapping("/team")
@Slf4j
public class TeamController {

    @Resource
    private UserService userService;

    @Resource
    private TeamService teamService;

    @Resource
    private UserTeamService userTeamService;

    @PostMapping("/add")
    public BaseResponse<Long> addTeam(@RequestBody TeamAddRequest teamAddRequest, HttpServletRequest request) {
        if (teamAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        Team team = new Team();
        BeanUtils.copyProperties(teamAddRequest, team);
        Long teamId = teamService.addTeam(team, loginUser);
        return ResultUtils.success(teamId);
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteTeam(@RequestBody TeamDeleteRequest teamDeleteRequest, HttpServletRequest request) {
        if (teamDeleteRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        boolean result = teamService.deleteTeam(teamDeleteRequest, loginUser);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return ResultUtils.success(true);
    }

    @PostMapping("/update")
    public BaseResponse<Boolean> updateTeam(@RequestBody TeamUpdateRequest teamUpdateRequest, HttpServletRequest request) {
        if (teamUpdateRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        boolean result = teamService.updateTeam(teamUpdateRequest, loginUser);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return ResultUtils.success(true);
    }

    @GetMapping("/get")
    public BaseResponse<Team> getTeamById(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team team = teamService.getById(id);
        if (team == null) {
            throw new BusinessException(ErrorCode.NULL_ERROR);
        }
        return ResultUtils.success(team);
    }

    @GetMapping("/list")
    public BaseResponse<List<TeamUserVO>> listTeams(TeamQuery teamQuery, HttpServletRequest request) {
        if (teamQuery == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        // 查询队伍列表
        List<TeamUserVO> teamList = teamService.listTeams(teamQuery, loginUser);
        // 判断用户是否已加入队伍
        List<Long> teamIdList = teamList.stream().map(TeamUserVO::getId).collect(Collectors.toList());
        QueryWrapper<UserTeam> userTeamQueryWrapper = new QueryWrapper<>();
        userTeamQueryWrapper.eq("userId", loginUser.getId());
        userTeamQueryWrapper.in("teamId", teamIdList);
        List<UserTeam> userTeamList = userTeamService.list(userTeamQueryWrapper);
        // 将已加入的队伍id取出
        Set<Long> hasJoinTeamIdList = userTeamList.stream().map(UserTeam::getTeamId).collect(Collectors.toSet());
        teamList.forEach(team -> {
            boolean hasJoin = hasJoinTeamIdList.contains(team.getId());
            team.setHasJoin(hasJoin);
        });
        // 查询已加入队伍的人数
        QueryWrapper<UserTeam> hasJoinNumQueryWrapper = new QueryWrapper<>();
        hasJoinNumQueryWrapper.in("teamId", teamIdList);
        // 队伍 id => 加入这个队伍的用户列表
        Map<Long, List<UserTeam>> hasJoinTeamNum = userTeamService.list(hasJoinNumQueryWrapper).stream().collect(Collectors.groupingBy(UserTeam::getTeamId));
        teamList.forEach(team -> {
            team.setHasJoinNum(hasJoinTeamNum.getOrDefault(team.getId(), new ArrayList<>()).size());
        });
        for (TeamUserVO teamUserVO : teamList) {
            teamUserVO.setPassword(StringUtils.EMPTY);
        }
        return ResultUtils.success(teamList);
    }

    //todo 分页未实现
    @GetMapping("/list/page")
    public BaseResponse<Page<Team>> listTeamsByPage(TeamQuery teamQuery) {
        if (teamQuery == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team team = new Team();
        BeanUtils.copyProperties(teamQuery, team);
        Page<Team> page = new Page<>(teamQuery.getPageNum(), teamQuery.getPageSize());
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>(team);
        Page<Team> pageResult = teamService.page(page, queryWrapper);
        return ResultUtils.success(pageResult);
    }

    @PostMapping("/join")
    public BaseResponse<Boolean> joinTeam(@RequestBody TeamJoinRequest teamJoinRequest, HttpServletRequest request) {
        if (teamJoinRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        boolean result = teamService.joinTeam(teamJoinRequest, loginUser);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return ResultUtils.success(true);
    }

    @PostMapping("/quit")
    public BaseResponse<Boolean> quitTeam(@RequestBody TeamQuitRequest teamQuitRequest, HttpServletRequest request) {
        if (teamQuitRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        boolean result = teamService.quitTeam(teamQuitRequest, loginUser);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return ResultUtils.success(true);
    }

    /**
     * 获取我创建的队伍
     * @param teamQuery
     * @param request
     * @return
     */
    @GetMapping("/list/my/create")
    public BaseResponse<List<TeamUserVO>> listMyCreateTeams(TeamQuery teamQuery, HttpServletRequest request) {
        if (teamQuery == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        Long loginUserId = loginUser.getId();
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", loginUserId);
        List<Team> teamsByLoginUser = teamService.list(queryWrapper);
        List<Long> myTeams = teamsByLoginUser.stream().map(Team::getId).collect(Collectors.toList());
        teamQuery.setIdList(myTeams);
        teamQuery.setUserId(loginUserId);
        List<TeamUserVO> teamList = teamService.listTeamsByJoin(teamQuery, loginUser);
        //脱敏密码信息，不然前端可以看到
        for (TeamUserVO teamUserVO : teamList) {
            teamUserVO.setPassword(StringUtils.EMPTY);
        }
        getJoinTeamUserNum(teamList);
        return ResultUtils.success(teamList);
    }

    /**
     * 获取我加入的队伍
     * @param teamQuery
     * @param request
     * @return
     */
    @GetMapping("/list/my/join")
    public BaseResponse<List<TeamUserVO>> listMyJoinTeams(TeamQuery teamQuery, HttpServletRequest request) {
        if (teamQuery == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        // 根据用户id去关系表中查出已加入队伍信息
        QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", loginUser.getId());
        List<UserTeam> userTeamList = userTeamService.list(queryWrapper);
        // 如果未加入队伍，返回空列表
        if (userTeamList.size() <= 0) {
            return ResultUtils.success(new ArrayList<TeamUserVO>());
        }
        // 根据队伍id分组
        Map<Long, List<UserTeam>> listMap = userTeamList.stream()
                .collect(Collectors.groupingBy(UserTeam::getTeamId));
        // 取出不重复的队伍id集合
        List<Long> idList = new ArrayList<>(listMap.keySet());
        teamQuery.setIdList(idList);
        List<TeamUserVO> teamList = teamService.listTeamsByJoin(teamQuery, loginUser);
        for (TeamUserVO teamUserVO : teamList) {
            teamUserVO.setPassword(StringUtils.EMPTY);
        }
        getHasJoinTeam(loginUser, teamList);
        getJoinTeamUserNum(teamList);
        return ResultUtils.success(teamList);
    }

    /**
     * 查询是否加入队伍（返回给前端的按钮展示）
     * @param loginUser
     * @param teamList
     */
    private void getHasJoinTeam(User loginUser, List<TeamUserVO> teamList) {
        // 判断用户是否已加入队伍
        List<Long> teamIdList = teamList.stream().map(TeamUserVO::getId).collect(Collectors.toList());
        QueryWrapper<UserTeam> userTeamQueryWrapper = new QueryWrapper<>();
        userTeamQueryWrapper.eq("userId", loginUser.getId());
        userTeamQueryWrapper.in("teamId", teamIdList);
        // 将已加入的队伍id取出
        Set<Long> hasJoinTeamIdList = userTeamService.list(userTeamQueryWrapper).stream().map(UserTeam::getTeamId).collect(Collectors.toSet());
        teamList.forEach(team -> {
            boolean hasJoin = hasJoinTeamIdList.contains(team.getId());
            team.setHasJoin(hasJoin);
        });
    }

    /**
     * 查询已加入队伍的人数
     * @param teamList
     */
    private void getJoinTeamUserNum(List<TeamUserVO> teamList) {
        List<Long> teamIdList = teamList.stream().map(TeamUserVO::getId).collect(Collectors.toList());
        // 查询已加入队伍的人数
        QueryWrapper<UserTeam> hasJoinNumQueryWrapper = new QueryWrapper<>();
        hasJoinNumQueryWrapper.in("teamId", teamIdList);
        // 队伍 id => 加入这个队伍的用户列表
        Map<Long, List<UserTeam>> hasJoinTeamNum = userTeamService.list(hasJoinNumQueryWrapper).stream().collect(Collectors.groupingBy(UserTeam::getTeamId));
        teamList.forEach(team -> {
            team.setHasJoinNum(hasJoinTeamNum.getOrDefault(team.getId(), new ArrayList<>()).size());
        });
    }

}
