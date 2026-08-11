import { createStore } from 'vuex'
import axios from 'axios';
import router from '../router'
import { ElMessage } from 'element-plus';

export default createStore({
    state: {
        // 是否加载中
        isLoading: false,
        // 是否登录
        isLogin: false,
        // 当前用户
        user: {},
        // 分区列表
        channels: [],
    },
    mutations: {
        // 更新登录状态
        updateIsLogin(state, isLogin) {
            state.isLogin = isLogin;
        },
        // 更新当前用户
        updateUser(state, user) {
            state.user = user;
            // console.log("更新vuex中用户信息: ", state.user);
        },
        // 更新分区列表
        updateChannels(state, channels) {
            state.channels = channels;
            // console.log("vuex中的分区: ", state.channels);
        },
    },
    actions: {
        // 获取当前用户信息（菲比啾比后端：GET /api/users/me，返回 UserVO）
        async getPersonalInfo(context) {
            // 这里为了更方便捕捉到错误后做出反应，就不使用封装的函数了
            const result = await axios.get("/api/users/me", {
                headers: {
                    Authorization: "Bearer " + localStorage.getItem("teri_token"),
                },
            })
            .catch(() => {
                // 一般这里捕抓到异常就表示token失效了，所以直接清空浏览器缓存就好了，不需要调用退出函数了
                // 修改当前的登录状态
                context.state.isLogin = false;
                // 清空user信息
                context.state.user = {};
                // 清除本地token缓存
                localStorage.removeItem("teri_token");
                ElMessage.error("请登录后查看");
                router.push("/login");
            });
            if (!result) return;
            if (result.data.code !== 200) {
                // 不是返回200码的都是认证失败，要清除缓存
                // 修改当前的登录状态
                context.state.isLogin = false;
                // 清空user信息
                context.state.user = {};
                // 清除本地token缓存
                localStorage.removeItem("teri_token");
                ElMessage.error(result.data.message);
                router.push("/login");
            }
            if (result.data.code === 200) {
                const user = result.data.data;
                // 管理端要求管理员身份 role: 1 管理员 2 超级管理员
                if (!user.role || user.role === 0) {
                    context.state.isLogin = false;
                    context.state.user = {};
                    localStorage.removeItem("teri_token");
                    ElMessage.error("您不是管理员，无权访问");
                    router.push("/login");
                    return;
                }
                context.commit("updateUser", user);
                context.state.isLogin = true;
            }
        },
        
        // 获取分区列表（菲比啾比后端：GET /api/category，需要登录）
        async loadChannels(context) {
            const token = localStorage.getItem("teri_token");
            if (!token) return; // 未登录时后端会返回 401，这里直接跳过
            const result = await axios.get("/api/category", {
                headers: { Authorization: "Bearer " + token },
            }).catch(() => null);
            if (!result || result.data.code !== 200 || !result.data.data) return;
            // 后端返回 CategoryParentVO: {mcId, mcName, children:[{scId, scName, description, rcmTags}]}
            // 适配为旧版组件使用的形状: {mcId, mcName, scList:[{mcId, scId, scName, descr, rcmTag}]}
            const channels = result.data.data.map(parent => ({
                mcId: parent.mcId,
                mcName: parent.mcName,
                scList: (parent.children || []).map(child => ({
                    mcId: parent.mcId,
                    scId: child.scId,
                    scName: child.scName,
                    descr: child.description,
                    rcmTag: child.rcmTags || [],
                })),
            }));
            context.commit("updateChannels", channels);
        },

        // 退出登录
        logout(context) {
            // 先修改状态再发送请求，防止token过期导致退出失败
            // 修改当前的登录状态
            context.state.isLogin = false;
            // 清空user信息
            context.state.user = {};
            router.push("/login");
            // 发送退出请求（菲比啾比后端：POST /api/auth/logout，服务端把 jti 写入 Redis 黑名单）
            axios.post("/api/auth/logout", null, {
                headers: {
                    Authorization: "Bearer " + localStorage.getItem("teri_token"),
                },
            }).catch(() => {});
            // 清除本地token缓存
            localStorage.removeItem("teri_token");
        }
    }
})