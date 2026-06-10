package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/artist")
public class AdminArtistController {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ArtistService artistService;

    @Autowired
    private UserService userService;

    @Autowired
    private ArtistMapper artistMapper;

    private User getCurrentUser() {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return userService.getOne(new LambdaQueryWrapper<User>().eq(User::getId, Long.valueOf(userId)));
    }

    /**
     * 1. 获取所有艺人列表 (供发布演出时的下拉框使用)
     */
    /**
     * 1. 动态获取艺人列表 (供发布演出时的下拉框远程搜索使用)
     */
    @GetMapping("/listAll")
    public Result<IPage<Artist>> listAllArtists(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {

        // 🚨 核心逻辑：如果不输入关键词，直接返回空的分页对象，不查数据库！
        if (!StringUtils.hasText(keyword)) {
            return Result.success(new Page<>(current, size));
        }

        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();

        wrapper.and(w -> w.like(Artist::getName, keyword));

        // 按照创建时间倒序排
        wrapper.orderByDesc(Artist::getCreateTime);

        // 返回分页数据
        return Result.success(artistService.page(new Page<>(current, size), wrapper));
    }


    //分页获取艺人列表 (支持按名字模糊搜索)
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == '1'")
    public Result<IPage<Artist>> getArtistPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) { // 🚨 接收 keyword 参数

        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();

        // 👇 核心：多字段联合搜索，必须用 and() 把内部的 or 包裹起来
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Artist::getName, keyword)
                    .or()
                    .like(Artist::getRegion, keyword)
                    .or()
                    .like(Artist::getStyle, keyword));
        }

        wrapper.orderByDesc(Artist::getCreateTime);
        return Result.success(artistService.page(new Page<>(current, size), wrapper));
    }

    //编辑艺人信息
    @PutMapping("/update")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == 'admin'")
    public Result<String> updateArtist(@RequestBody Artist artist) {
        if (artist.getId() == null) {
            return Result.error("艺人ID不能为空");
        }

        User currentUser = getCurrentUser();
        boolean isSuperAdmin = currentUser.getId() == 1L;

        Artist oldArtist = artistService.getById(artist.getId());
        if (oldArtist == null) {
            throw new BusinessException("艺人不存在");
        }

        if (!StringUtils.hasText(artist.getAvatarUrl())) {
            throw new BusinessException("头像不能为空！");
        }

        // 超管：直接生效
        if (isSuperAdmin) {
            artist.setAuditStatus(1);
            artist.setEditAuditStatus(null);
            artist.setPendingPayload(null);
            artist.setAuditSubmitTime(LocalDateTime.now());
            artistService.updateById(artist);
            return Result.success("修改成功");
        }

        // 普通管理员：新增待审核不能直接改
        if (Objects.equals(oldArtist.getAuditStatus(), 0)) {
            throw new BusinessException("该艺人正在审核中，如需修改，请先撤销审核申请");
        }

        // 普通管理员：修改待审核不能再次改
        if (Objects.equals(oldArtist.getEditAuditStatus(), 0)) {
            throw new BusinessException("该艺人修改正在审核中，如需再次修改，请先撤销审核申请");
        }

        // 普通管理员编辑已通过：只存快照
        if (Objects.equals(oldArtist.getAuditStatus(), 1)) {
            try {
                oldArtist.setPendingPayload(objectMapper.writeValueAsString(artist));
                oldArtist.setEditAuditStatus(0);
                oldArtist.setAuditSubmitTime(LocalDateTime.now());
                artistService.updateById(oldArtist);
                return Result.success("艺人修改已提交审核，审核通过前客户端仍展示原信息");
            } catch (Exception e) {
                throw new BusinessException("保存艺人修改审核快照失败");
            }
        }

        // 驳回/撤销后重新提交
        artist.setAuditStatus(0);
        artist.setEditAuditStatus(null);
        artist.setPendingPayload(null);
        artist.setAuditSubmitTime(LocalDateTime.now());
        artistService.updateById(artist);

        return Result.success("艺人已重新提交审核");
    }

    // 修改艺人状态 (例如：下架、恢复)
    @PutMapping("/{id}/status/{status}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == 'admin'")
    public Result<String> changeStatus(@PathVariable Long id, @PathVariable Integer status) {
        Artist artist = new Artist();
        artist.setId(id);
        // 假设 1 是正常，2 是下架
        artist.setAuditStatus(status);
        artistService.updateById(artist);
        return Result.success("状态更新成功");
    }

    /**
     * 2. 提交新增艺人 (进入待审核状态)
     */
    @PostMapping("/add")
    public Result<String> addArtist(@RequestBody Artist artist) {
        User currentUser = getCurrentUser();
        boolean isSuperAdmin = (currentUser.getId() == 1L);

        if(!StringUtils.hasText(artist.getAvatarUrl()) || !StringUtils.hasText(artist.getDescription())){
            throw new BusinessException("头像或介绍不能为空！");
        }

        artist.setCreateBy(currentUser.getId());

        // 超管免审，普通管理员强制待审核
        if (isSuperAdmin) {
            artist.setAuditStatus(1); // 1: 审核通过
        } else {
            artist.setAuditStatus(0); // 0: 待审核
        }

        artistMapper.insert(artist);
        System.out.println(artist.getAvatarUrl());
        return Result.success("艺人提交成功");
    }

    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == 'admin'")
    public Result<String> deleteArtist(@PathVariable Long id) {
        // 如果你的数据库配置了逻辑删除 (deleted 字段)，这里会自动执行逻辑删除
        // 如果没有配置，这里就是真实的物理删除
        artistService.removeById(id);
        return Result.success("删除成功");
    }


    /**
     * 1. 分页获取待审核的艺人列表
     * 权限：仅限拥有 audit:manage 权限的审核员或超管
     */
    @GetMapping("/audit-list")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == 'admin'")
    public Result<IPage<Artist>> getPendingArtists(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "5") Integer size) {

        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
        // 🚨 0 代表待审核状态，你可以根据你的数据库实际约定进行调整
        wrapper.and(w -> w
                .eq(Artist::getAuditStatus, 0)
                .or()
                .eq(Artist::getEditAuditStatus, 0)
        );
        wrapper.orderByDesc(Artist::getAuditSubmitTime);

        IPage<Artist> pageData = artistService.page(new Page<>(current, size), wrapper);
        return Result.success(pageData);
    }

    /**
     * 2. 审核艺人 (通过 / 驳回)
     */
    @PutMapping("/audit/{id}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == 'admin'")
    public Result<String> auditArtist(@PathVariable Long id, @RequestParam Boolean isPass) {
        Artist artist = artistService.getById(id);
        if (artist == null) {
            return Result.error("该艺人记录不存在");
        }

        // 修改审核
        if (Objects.equals(artist.getEditAuditStatus(), 0)) {
            if (isPass) {
                try {
                    Artist pending = objectMapper.readValue(artist.getPendingPayload(), Artist.class);
                    pending.setId(id);
                    pending.setAuditStatus(1);
                    pending.setAuditSubmitTime(LocalDateTime.now());
                    artistService.updateById(pending);
                    artistService.update(
                            new LambdaUpdateWrapper<Artist>()
                                    .eq(Artist::getId, id)
                                    .set(Artist::getEditAuditStatus, null)
                                    .set(Artist::getPendingPayload, null)
                                    .set(Artist::getAuditSubmitTime, LocalDateTime.now())
                    );
                    return Result.success("艺人修改审核已通过，客户端信息已同步更新");
                } catch (Exception e) {
                    throw new BusinessException("解析艺人修改审核快照失败");
                }
            } else {
                artistService.update(
                        new LambdaUpdateWrapper<Artist>()
                                .eq(Artist::getId, id)
                                .set(Artist::getEditAuditStatus, 2)
                                .set(Artist::getPendingPayload, null)
                                .set(Artist::getAuditSubmitTime, LocalDateTime.now())
                );
                return Result.success("已驳回艺人修改申请，客户端继续展示原信息");
            }
        }

        // 新增审核
        if (Objects.equals(artist.getAuditStatus(), 0)) {
            artist.setAuditStatus(isPass ? 1 : 2);
            artistService.updateById(artist);
            return Result.success(isPass ? "艺人已通过审核" : "已驳回该艺人的入驻申请");
        }

        throw new BusinessException("当前艺人没有待审核申请");
    }

    @PutMapping("/revoke/{id}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == 'admin'")
    public Result<String> revokeArtistAudit(@PathVariable Long id) {
        Artist artist = artistService.getById(id);
        if (artist == null) {
            throw new BusinessException("艺人不存在");
        }

        if (Objects.equals(artist.getAuditStatus(), 0)) {
            artist.setAuditStatus(3);
            artistService.updateById(artist);
            return Result.success("已撤销艺人新增审核申请，可重新编辑后提交");
        }

        if (Objects.equals(artist.getEditAuditStatus(), 0)) {
            artistService.update(
                    new LambdaUpdateWrapper<Artist>()
                            .eq(Artist::getId, id)
                            .set(Artist::getEditAuditStatus, null)
                            .set(Artist::getPendingPayload, null)
                            .set(Artist::getAuditSubmitTime, LocalDateTime.now())
            );

            return Result.success("已撤销艺人修改审核申请，客户端信息未受影响");
        }

        throw new BusinessException("当前状态无需撤销审核");
    }

    @PutMapping("/confirm-edit-reject/{id}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == '1'")
    public Result<String> confirmArtistEditReject(@PathVariable Long id) {
        Artist artist = artistService.getById(id);
        if (artist == null) {
            throw new BusinessException("艺人不存在");
        }

        if (!Objects.equals(artist.getEditAuditStatus(), 2)) {
            throw new BusinessException("当前艺人没有待确认的修改驳回状态");
        }

        artistService.update(
                new LambdaUpdateWrapper<Artist>()
                        .eq(Artist::getId, id)
                        .set(Artist::getEditAuditStatus, null)
                        .set(Artist::getPendingPayload, null)
                        .set(Artist::getAuditSubmitTime, LocalDateTime.now())
        );

        return Result.success("已确认修改驳回结果");
    }

}