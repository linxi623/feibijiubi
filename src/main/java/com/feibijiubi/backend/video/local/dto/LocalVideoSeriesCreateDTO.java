package com.feibijiubi.backend.video.local.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建本地作品的请求体。
 *
 * <p>只有 {@code title} 一个字段。{@code localIdentity}、{@code createdBy}、{@code vid}、
 * {@code durationSeconds} 一律<b>不由请求提供</b>：身份由服务端生成，创建者取自登录态
 * {@code currentUserId}，后两者在本片没有写入路径。</p>
 *
 * <p>关于“多传的字段”：当前 JSON 策略忽略未知属性（Spring Boot 默认不因未知字段失败），
 * 所以请求里带上 {@code vid} / {@code durationSeconds} / {@code createdBy} / {@code localIdentity}
 * <b>不会</b>被报错，但也<b>绝不会</b>被应用 —— 本类没有这些字段，服务层也不读它们。
 * 这是“忽略且不生效”，不是“校验并拒绝”。</p>
 *
 * <p>校验分两层：Bean Validation 挡住空白与超过 200 的原文；服务层再对 trim 后的文本复核，
 * 因此直接调用服务（不经 MVC）同样受同一套长度约束。{@code title} 是自由文本，本片不做清洗，
 * 渲染转义由前端负责。</p>
 */
@Data
public class LocalVideoSeriesCreateDTO {

    @NotBlank(message = "title 不能为空")
    @Size(max = 200, message = "title 长度不能超过 200")
    private String title;
}
