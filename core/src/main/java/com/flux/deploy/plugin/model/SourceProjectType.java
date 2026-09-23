package com.flux.deploy.plugin.model;

/**
 * 源工程类型枚举
 *
 * <p>区分「工程」下拉中两类可部署工程：</p>
 * <ul>
 *   <li>{@link #MAVEN} - 后端 Maven 模块（pom.xml），产物为 target/ 下的 jar/war 单文件</li>
 *   <li>{@link #VUE} - Vue 前端模块工程（serve.yaml + package.json 含 zip:module 脚本），
 *       产物为按业务模块打出的 {@code {content}_{模块号}.zip}</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-08-13
 */
public enum SourceProjectType {

    /** 后端 Maven 模块，产物 jar/war 单文件 */
    MAVEN,
    /** Vue 前端模块工程，产物为模块 zip */
    VUE
}
