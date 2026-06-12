package com.avemonica.ticket.config;

public final class AuthExp {

    private AuthExp() {}

    /**
     * 超管账号：当前项目里约定用户 ID = 1
     */
    public static final String SUPER = "authentication.name == '1'";

    /**
     * 审核权限：演出 / 艺人 / Banner 审核
     */
    public static final String AUDIT_MANAGE =
            "hasAuthority('audit:manage') or " + SUPER;

    /**
     * 演出权限
     */
    public static final String EVENT_VIEW =
            "hasAuthority('event:view') or " + SUPER;

    public static final String EVENT_PUBLISH =
            "hasAuthority('event:publish') or " + SUPER;

    public static final String EVENT_EDIT =
            "hasAuthority('event:edit') or " + SUPER;

    public static final String EVENT_WRITE =
            "hasAnyAuthority('event:publish', 'event:edit') or " + SUPER;

    public static final String EVENT_TAKEDOWN =
            "hasAnyAuthority('audit:manage', 'event:edit') or " + SUPER;

    /**
     * Banner 权限
     * banner:manage 已包含 banner:view 的业务含义，但 Spring 不会自动继承，
     * 所以查看时要同时允许 banner:view 和 banner:manage。
     */
    public static final String BANNER_VIEW =
            "hasAnyAuthority('banner:view', 'banner:manage') or " + SUPER;

    public static final String BANNER_MANAGE =
            "hasAuthority('banner:manage') or " + SUPER;

    /**
     * 艺人权限
     * artist:manage 已包含 artist:view 的业务含义，但 Spring 不会自动继承，
     * 所以查看时要同时允许 artist:view 和 artist:manage。
     */
    public static final String ARTIST_VIEW =
            "hasAnyAuthority('artist:view', 'artist:manage') or " + SUPER;

    public static final String ARTIST_MANAGE =
            "hasAuthority('artist:manage') or " + SUPER;

    /**
     * 发布演出时需要搜索艺人，审核员也需要查看艺人。
     */
    public static final String ARTIST_SELECTOR =
            "hasAnyAuthority('artist:view', 'artist:manage', 'event:publish', 'event:edit', 'audit:manage') or " + SUPER;

    /**
     * 演出管理方可以提交新艺人审核，艺人管理员也可以新增艺人。
     */
    public static final String ARTIST_ADD =
            "hasAnyAuthority('artist:manage', 'event:publish') or " + SUPER;
}