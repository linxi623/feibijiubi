<template>
    <div class="flex-fill">
        <div class="v-container">
            <div class="video-detail__layout">
                <div class="left">
                    <div id="player" class="player">
                        <video :src="video.videoUrl" controls></video>
                    </div>
                    <div class="v-card options">
                        <div class="options-top">
                            <div class="status" v-if="video.status === 0">
                                <i class="iconfont icon-shenhezhong"></i>
                                <span>待审核</span>
                            </div>
                            <div class="status" v-if="video.status === 1">
                                <i class="iconfont icon-wancheng"></i>
                                <span>已通过</span>
                            </div>
                            <div class="status" v-if="video.status === 2">
                                <i class="iconfont icon-shibai"></i>
                                <span>未通过</span>
                            </div>
                            <div class="items">
                                <!-- 菲比啾比后端审核接口只支持 APPROVED / REJECTED，暂无“永久删除”能力 -->
                                <el-button v-if="video.status === 0" type="success" plain class="options-item pass"
                                    :disabled="reviewing" @click="reviewVideo('APPROVED')">
                                    <el-icon v-if="isMiniWidth"><Select /></el-icon>
                                    <span v-else>通过审核</span>
                                </el-button>
                                <el-button v-if="video.status === 0" type="warning" plain class="options-item no-pass"
                                    :disabled="reviewing" @click="beforeReject">
                                    <el-icon v-if="isMiniWidth"><CloseBold /></el-icon>
                                    <span v-else>不予过审</span>
                                </el-button>
                            </div>
                        </div>
                    </div>
                </div>                
                <div class="detail">
                    <div class="v-card detail-card">
                        <div class="detail-item">
                            <div class="item-title">标题</div>
                            <div class="item-content">{{ video.title }}</div>
                        </div>
                        <div class="detail-item">
                            <div class="item-title">类型</div>
                            <div class="item-content">
                                <span class="type" v-if="video.sourceType === 1">自制</span>
                                <span class="type" v-if="video.sourceType === 2">转载</span>
                            </div>
                        </div>
                        <div class="detail-item">
                            <div class="item-title">分区</div>
                            <div class="item-content">
                                {{ category.mcName }} &nbsp;→&nbsp; {{ category.scName }}
                            </div>
                        </div>
                        <div class="detail-item">
                            <div class="item-title">标签</div>
                            <div class="item-content">
                                <div class="tag-container" v-for="(item, index) in tags" :key="index">
                                    {{ item }}
                                </div>
                            </div>
                        </div>
                        <div class="detail-item">
                            <div class="item-title">简介</div>
                            <div class="item-content"><span class="v-text descr" v-html="formatText(video.description)"></span></div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import { linkify } from '@/utils/utils.js';
import { ElMessage, ElMessageBox } from 'element-plus';

export default {
    name: "VideoDetail",
    data() {
        return {
            video: {},  // 视频信息
            user: {},   // 投稿用户信息
            category: {},   // 视频分区信息
            tags: [],   // 投稿标签
            isMiniWidth: false, // 判断是否小窗
            reviewing: false,
        }
    },
    methods: {
        // 请求
        // 获取视频详细信息（菲比啾比后端：GET /api/admin/videos/{vid}，返回扁平的 AdminVideoDetailVO）
        async getVideoDetail() {
            const res = await this.$get(`/admin/videos/${this.$route.params.vid}`, {
                headers: {
                    Authorization: "Bearer " + localStorage.getItem("teri_token"),
                },
            });
            if (res.data.data) {
                const data = res.data.data;
                this.video = data;
                this.user = {
                    uid: data.uid,
                    nickname: data.nickname,
                    avatarUrl: data.avatarUrl,
                };
                // 后端只返回 mcId/scId，分区名称从 Vuex 分区列表解析
                this.category = this.resolveCategory(data.mcId, data.scId);
                // 菲比啾比的标签用逗号分隔
                this.tags = (data.tags || "").split(/[,，]/).filter(tag => tag.trim() !== "");
            }
        },

        // 根据 mcId/scId 从分区列表解析出分区名称
        resolveCategory(mcId, scId) {
            const channels = this.$store.state.channels || [];
            const parent = channels.find(item => item.mcId === mcId);
            const child = parent ? (parent.scList || []).find(item => item.scId === scId) : null;
            return {
                mcName: parent ? parent.mcName : mcId,
                scName: child ? child.scName : scId,
            };
        },

        // 审核视频（菲比啾比后端：POST /api/admin/videos/{vid}/review，body: {result, reason}）
        async reviewVideo(result, reason = null) {
            if (this.reviewing || this.video.status !== 0) return;
            this.reviewing = true;
            this.$store.state.isLoading = true;
            const res = await this.$post(`/admin/videos/${this.$route.params.vid}/review`, {
                result: result,
                reason: reason,
            }, {
                headers: {
                    Authorization: "Bearer " + localStorage.getItem("teri_token"),
                }
            }).catch(() => null);
            if (!res || !res.data) {
                ElMessage.error("特丽丽被玩坏了(¯﹃¯)");
                this.$store.state.isLoading = false;
                this.reviewing = false;
                return;
            }
            if (res.data.code === 200) {
                ElMessage.success(result === 'APPROVED' ? "已通过审核" : "已驳回");
                await this.goToNextPendingVideo();
            }
            this.$store.state.isLoading = false;
            this.reviewing = false;
        },

        async goToNextPendingVideo() {
            const res = await this.$get('/admin/videos/page', {
                params: { page: 1, status: 0, quantity: 1 },
                headers: {
                    Authorization: "Bearer " + localStorage.getItem("teri_token"),
                },
            }).catch(() => null);
            const videos = res && res.data && Array.isArray(res.data.data) ? res.data.data : [];
            if (videos.length > 0) {
                await this.$router.replace({
                    name: 'videoDetail',
                    params: { vid: videos[0].vid },
                });
                await this.getVideoDetail();
                return;
            }
            await this.$router.replace('/review/video/form');
        },

        // 驳回前必须填写原因（后端强制要求）
        beforeReject() {
            ElMessageBox.prompt('请输入驳回原因（必填）', '不予过审', {
                confirmButtonText: '确定驳回',
                cancelButtonText: '取消',
                inputValidator: (value) => {
                    if (!value || value.trim() === '') return '驳回视频必须填写原因';
                    return true;
                },
            })
            .then(({ value }) => {
                this.reviewVideo('REJECTED', value.trim());
            })
            .catch(() => {})
        },

        // 事件
        // 窗口大小改变时更新 player 的高度
        updatePlayerHeight() {
            const playerElement = document.getElementById('player');
            const playerWidth = playerElement.offsetWidth;
            const playerHeight = playerWidth * (9 / 16);
            playerElement.style.height = `${playerHeight}px`;
        },

        // 判断是否窗
        // 判断是否小窗
        changeWidth() {
            if (window.innerWidth < 480) {
                this.isMiniWidth = true;
            } else {
                this.isMiniWidth = false;
            }
        },

        // 将文本中的链接格式化成超链接
        formatText(text) {
            return linkify(text);
        },
    },
    async created() {
        this.changeWidth();
        await this.getVideoDetail();
    },
    mounted() {
        this.updatePlayerHeight();
        window.addEventListener('resize', this.updatePlayerHeight);
        window.addEventListener('resize', this.changeWidth);
    },
    unmounted() {
        window.removeEventListener('resize', this.updatePlayerHeight);
        window.removeEventListener('resize', this.changeWidth);
    }
}
</script>

<style scoped>
.v-container {
    position: relative;
}

.video-detail__layout {
    position: relative;
    width: 100%;
    display: flex;
}

.left {
    width: 66%;
    max-width: 672px;
}

.player {
    box-shadow: 2px 2px 10px #0000003f;
    background-color: black;
    width: 100%;
}

.player video {
    width: 100%;
    height: 100%;
}

.options {
    margin-top: 16px;
}

.options-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    height: 64px;
    padding: 0 16px;
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

.options-item {
    padding: 0 10px;
}

.detail {
    flex: 1;
    margin: 0 0 0 16px;
    min-width: 400px;
    color: var(--text2);
}

.detail-card {
    padding: 0 16px 30px 20px;
}

.detail-item {
    display: flex;
    margin-top: 20px;
    min-height: 25px;
}

.item-title {
    flex: 0 0 auto;
    width: 70px;
    color: var(--text1);
    font-size: 16px;
    font-weight: 600;
}

.item-content {
    display: flex;
    flex: 1;
    flex-wrap: wrap;
}

.type {
    flex: 0 0 auto;
    width: 45px;
}

.icon-jinzhi {
    font-size: 14px;
    color: var(--stress_red);
    margin-right: 4px;
}

.tag-container {
    text-align: center;
    padding: 0 12px;
    margin: 0px 12px 12px 0;
    height: 25px;
    border-radius: 14px;
    background: #f1f2f3;
    font-size: 12px;
    line-height: 25px;
    border: none;
}

.descr {
    width: 100%;
    padding: 10px;
    background-color: #fafafa;
    border: 1px solid #eee;
    border-radius: 8px;
}

@media (max-width: 700px) {
    .video-detail__layout {
        flex-direction: column;
    }

    .left {
        width: auto;
    }

    .detail {
        margin: 16px 0 0 0;
        min-width: auto;
    }
    
    .item-title {
        width: 50px;
    }
}

@media (min-width: 700.1px) and (max-width: 800px) {
    .detail {
        min-width: 300px;
    }

    .item-title {
        width: 50px;
    }
}
</style>
