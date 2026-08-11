<template>
    <div class="flex-fill">
        <div class="v-container">
            <div class="v-card">
                <div class="video-table-card">
                    <div class="v-table" v-loading="loading">
                            <div class="top">
                                <div class="navbar">
                                    <div class="bar-item" :class="videoStatus === 0 ? 'active' : ''" @click="changeStatus(0)">待审核</div>
                                    <div class="bar-item" :class="videoStatus === 1 ? 'active' : ''" @click="changeStatus(1)">已过审</div>
                                    <div class="bar-item" :class="videoStatus === 2 ? 'active' : ''" @click="changeStatus(2)">未过审</div>
                                </div>
                                <div class="top-right">
                                    <div class="refresh" @click="reloadVideos">刷新</div>
                                    <div class="total">第 {{ page }} 页</div>
                                </div>
                            </div>
                        <div class="v-table__wrapper">
                            <table>
                                <thead>
                                    <tr>
                                        <th style="min-width: 90px;">VID</th>
                                        <th style="min-width: 176px;">封面</th>
                                        <th style="min-width: 200px;">标题</th>
                                        <th style="min-width: 120px;">投稿用户</th>
                                        <th style="min-width: 100px;">时长</th>
                                        <th style="min-width: 150px;">投稿时间</th>
                                        <th style="min-width: 100px;">状态</th>
                                        <th style="min-width: 80px;"></th>
                                    </tr>
                                </thead>
                                <!-- 菲比啾比后端返回扁平的 AdminVideoListItemVO: {vid, uid, title, coverUrl, duration, createdAt} -->
                                <tbody v-if="videos.length != 0">
                                    <tr v-for="(item, index) in videos" :key="index">
                                        <td style="min-width: 90px;"># {{ item.vid }}</td>
                                        <td style="width: 176px;">
                                            <img :src="item.coverUrl" class="cover" alt="">
                                        </td>
                                        <td style="min-width: 200px;">{{ item.title }}</td>
                                        <td style="min-width: 120px;">
                                            <span class="nickname">UID: {{ item.uid }}</span>
                                        </td>
                                        <td style="min-width: 100px;">{{ formatDuration(item.duration) }}</td>
                                        <td style="min-width: 150px;">{{ formatDateTime(item.createdAt) }}</td>
                                        <td style="min-width: 100px;">
                                            <!-- 后端按状态过滤，当前列表项状态即当前所选标签页的状态 -->
                                            <div class="status" v-if="videoStatus === 0">
                                                <i class="iconfont icon-shenhezhong"></i>
                                                <span>待审核</span>
                                            </div>
                                            <div class="status" v-if="videoStatus === 1">
                                                <i class="iconfont icon-wancheng"></i>
                                                <span>已通过</span>
                                            </div>
                                            <div class="status" v-if="videoStatus === 2">
                                                <i class="iconfont icon-shibai"></i>
                                                <span>未通过</span>
                                            </div>
                                        </td>
                                        <td style="min-width: 80px;">
                                            <span
                                                class="detail-btn"
                                                @click="openNewPage({
                                                    name: 'videoDetail',
                                                    params: {vid: item.vid}
                                                })"
                                            >详情</span>
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                            <div class="no-more" v-if="!loading && videos.length == 0">
                                <img src="~assets/img/silly.png" alt="" >
                                <span>没有找到任何数据</span>
                            </div>
                        </div>
                        <!-- 后端没有查询总数的接口，改用上一页/下一页翻页 -->
                        <div class="v-table-page">
                            <el-button :disabled="page <= 1" @click="pageChange(page - 1)">上一页</el-button>
                            <span class="page-indicator">第 {{ page }} 页</span>
                            <el-button :disabled="!hasMore" @click="pageChange(page + 1)">下一页</el-button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>    
</template>

<script>
export default {
    name: "VideoReview",
    data() {
        return {
            videoStatus: 0,   // 要查询的视频状态，0 正在审核，1 通过审核，2 打回整改，3 违规封禁/已删源
            videos: [],
            page: 1,
            quantity: 10,     // 每页数量
            hasMore: false,   // 是否可能还有下一页（当前页填满即认为可能有）
            pagerCount: 7,
            loading: true,
        }
    },
    methods: {
        // 请求
        // 查询当前页的视频（菲比啾比后端：GET /api/admin/videos/page?page&status&quantity）
        async getVideos() {
            const res = await this.$get('/admin/videos/page', {
                params: {
                    page: this.page,
                    status: this.videoStatus,
                    quantity: this.quantity,
                },
                headers: {
                    Authorization: "Bearer " + localStorage.getItem("teri_token"),
                },
            });
            if (res && res.data && res.data.data) {
                this.videos = res.data.data;
            } else {
                this.videos = [];
            }
            // 后端没有 total 接口：当前页满员则认为可能有下一页
            this.hasMore = this.videos.length === this.quantity;
        },


        // 事件
        // 切换类型
        changeStatus(vstatus) {
            this.videoStatus = vstatus;
            if (this.page !== 1) {
                this.page = 1;
            }
            this.reloadVideos();
        },

        // 改变页码时的回调
        async pageChange(page) {
            if (page < 1) return;
            this.page = page;
            await this.reloadVideos();
        },

        // 重新加载视频列表
        async reloadVideos() {
            this.loading = true;
            await this.getVideos();
            this.loading = false;
        },

        // 格式化视频时长（秒 -> mm:ss）
        formatDuration(seconds) {
            if (seconds == null) return '--:--';
            const s = Math.round(Number(seconds));
            const mm = String(Math.floor(s / 60)).padStart(2, '0');
            const ss = String(s % 60).padStart(2, '0');
            return `${mm}:${ss}`;
        },

        // 格式化时间（ISO -> yyyy-MM-dd HH:mm）
        formatDateTime(value) {
            if (!value) return '';
            return String(value).replace('T', ' ').slice(0, 16);
        },

        // 判断是否小窗
        changeWidth() {
            if (window.innerWidth < 480) {
                this.pagerCount = 3;
            } else {
                this.pagerCount = 7;
            }
        },

        // 打开新标签页
        openNewPage(route) {
            window.open(this.$router.resolve(route).href, '_blank');
        },
    },
    async created() {
        this.changeWidth();
        await this.getVideos();
        this.loading = false;
    },
    mounted() {
        // 监听窗口大小变化，判断是否小窗
        window.addEventListener('resize', this.changeWidth);
    },
    unmounted() {
        window.removeEventListener('resize', this.changeWidth);
    },
}
</script>

<style scoped>
.video-table-card {
    height: calc(100vh - 96px);
    position: relative;
    overflow: hidden !important;
    overflow-anchor: none;
    -ms-overflow-style: none;
    touch-action: auto;
    -ms-touch-action: auto;
}

.v-table {
    --v-table-row-height: 120px;
}
.top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    height: 64px;
    border-bottom: 1px solid #e7e7e7;
}

.navbar, .top-right {
    display: flex;
    flex: 0 0 auto;
}
.top-right {
    margin-left: 100px;
}

.bar-item {
    flex: 0 0 auto;
    height: 64px;
    padding-bottom: 18px;
    padding-top: 26px;
    margin-left: 40px;
    font-size: 16px;
    color: #505050;
    cursor: pointer;
}

.active {
    color: var(--brand_pink);
    font-weight: 600;
    border-bottom: 3px solid var(--brand_pink);
}

.top-right>div {
    flex: 0 0 auto;
    line-height: 54px;
    margin-right: 30px;
    padding-top: 10px;
}

.refresh {
    cursor: pointer;
    color: var(--brand_blue);
}

.refresh:hover {
    color: var(--Lb6);
}

.v-table__wrapper {
    height: calc(100% - 150px);
}

.v-table__wrapper table {
    padding: 0 4px 8px;
}

.cover {
    height: 81px;
    width: 144px;
    object-fit: cover;
    box-shadow: 2px 2px 8px #0000001f;
}

.nickname {
    cursor: pointer;
}

.nickname:hover {
    color: var(--text1);
}

.category {
    color: #fff;
    line-height: 18px;
    padding: 2px 8px;
    border-radius: 10px;
}

.status {
    display: flex;
    align-items: center;
}

.status .iconfont {
    font-size: 12px;
    margin-right: 5px;
}

.icon-shenhezhong {
    color: var(--pay_yellow);
}

.icon-wancheng {
    color: var(--success_green);
}

.icon-shibai {
    color: var(--stress_red);
}

.detail-btn {
    cursor: pointer;
    color: var(--brand_blue);
}

.detail-btn:hover {
    color: var(--Lb6);
    text-decoration: underline;
}

.no-more {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 150px;
    width: 100%;
}

.no-more img {
    height: 80px;
}

.no-more span {
    font-size: 20px;
    color: var(--text3);
    line-height: 40px;
}

.v-table-page {
    width: 100%;
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 16px;
}

.page-indicator {
    color: var(--text2);
    font-size: 14px;
}
</style>