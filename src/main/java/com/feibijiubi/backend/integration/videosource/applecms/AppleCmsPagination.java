package com.feibijiubi.backend.integration.videosource.applecms;

/**
 * E2-B：上游自报的分页元数据快照（{@code page} / {@code pagecount} / {@code limit} / {@code total}）。
 *
 * <p><b>它只是上游那一刻的声明，不是本地可信事实，也不能用来驱动抓取。</b>具体地：</p>
 * <ul>
 *   <li>{@code pageCount} 与 {@code total} 都可能与后续实际响应不一致（上游在两次请求之间改数据、
 *       按权限截断、或干脆写错），因此<b>禁止</b>用它们做翻页循环条件、预分配数组、计算"还差多少页"或
 *       判断同步是否完成；翻页上限必须由本地同步任务自己的预算决定（E4）。</li>
 *   <li>{@code total} 是"上游声称的条目总数"，不是集数，也不是本系统的任何计数。</li>
 *   <li>空结果是合法的：{@code total=0}、{@code pageCount=0}、{@code page=1}，此时条目列表为空；
 *       本适配器不做"total 与 pageCount 是否自洽"之类的推断，因为它无法判断哪一个是错的。</li>
 * </ul>
 *
 * <p>四个字段都可能是 JSON 整数或"规范十进制字符串"（探测样本里 {@code limit} 就是字符串），解析时统一
 * 收紧成 {@code long}：小数、负数、前导零、首尾空白、科学计数、溢出都会让整份响应明确失败。</p>
 *
 * <p>compact constructor 只校验下面四个范围（纯数字不变量，与解析器常量无关）；它<b>不</b>校验"上游自报值
 * 是否自洽"，也不做任何推断——那不属于快照能保证的事。</p>
 *
 * @param page      页码，正整数
 * @param pageCount 上游自报的总页数，非负整数
 * @param limit     上游自报的单页条数，正整数（不代表本适配器的上限，本适配器固定最多解析 100 条）
 * @param total     上游自报的总条目数，非负整数
 */
public record AppleCmsPagination(long page, long pageCount, long limit, long total) {

    public AppleCmsPagination {
        if (page < 1) {
            throw new IllegalArgumentException("page 从 1 开始");
        }
        if (pageCount < 0 || total < 0) {
            throw new IllegalArgumentException("pageCount / total 不能为负");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit 至少为 1");
        }
    }
}
