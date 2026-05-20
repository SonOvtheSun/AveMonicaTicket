package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/artist")
public class AdminArtistController {

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
        if(!StringUtils.hasText(artist.getAvatarUrl())) {
            throw new BusinessException("头像不能为空！");
        }
        artistService.updateById(artist);
        return Result.success("修改成功");
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
        wrapper.eq(Artist::getAuditStatus, 0);
        // 按照创建时间倒序排，最新申请的在最上面
        wrapper.orderByDesc(Artist::getCreateTime);

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

        // 🚨 状态约定：1为审核通过，2为审核驳回
        artist.setAuditStatus(isPass ? 1 : 2);
        artistService.updateById(artist);

        return Result.success(isPass ? "艺人已通过审核" : "已驳回该艺人的入驻申请");
    }


}