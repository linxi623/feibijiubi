# Jackson `TypeReference<T>` 讲解

## 1. 先看这段方法

```java
public <T> T fromJson(String json, TypeReference<T> typeReference) {
    try {
        return objectMapper.readValue(json, typeReference);
    } catch (JsonProcessingException e) {
        throw new BusinessException(500, "JSON 转对象失败: " + e);
    }
}
```

这个方法的作用是：

> 把 JSON 字符串反序列化为带有泛型信息的 Java 对象。

例如：

- `List<Video>`
- `Map<String, User>`
- `PageResult<Video>`
- `Map<String, List<Video>>`

这里的 `TypeReference<T>` 是 Jackson 提供的类型信息载体：

```java
import com.fasterxml.jackson.core.type.TypeReference;
```

它主要用于告诉 Jackson：目标类型不仅是 `List`、`Map`，而且还要包含其中的具体泛型类型。

---

## 2. 为什么已经有 `Class<T>`，还需要 `TypeReference<T>`？

`JsonUtils` 中还有一个重载方法：

```java
public <T> T fromJson(String json, Class<T> type) {
    try {
        return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
        throw new BusinessException(500, "JSON 转对象失败: " + e);
    }
}
```

这个方法适合普通、确定的类型：

```java
User user = jsonUtils.fromJson(json, User.class);
Video video = jsonUtils.fromJson(json, Video.class);
```

因为 Java 可以直接使用：

```java
User.class
Video.class
```

但是，如果目标类型是 `List<Video>`，Java 不能这样写：

```java
// 这是不存在的语法
List<Video>.class
```

你只能写：

```java
List.class
```

问题是，`List.class` 只能说明目标对象是一个 `List`，却不能说明列表里的元素是 `Video`。

因此，下面的调用会丢失元素类型：

```java
List<Video> videos = jsonUtils.fromJson(json, List.class);
```

Jackson 只知道外层是 `List`，不知道内部元素应该转换成 `Video`，通常会把 JSON 对象转换成 `LinkedHashMap`。后续把元素当成 `Video` 使用时，就可能出现类型转换异常。

---

## 3. 根本原因：Java 的泛型类型擦除

Java 泛型主要在编译期间发挥作用。

例如：

```java
List<Video> videos;
List<User> users;
```

在源代码中，它们的类型明显不同；但经过泛型类型擦除后，运行期间二者的主要类型都是：

```java
List
```

也就是说，JVM 在运行时通过普通的 `List.class` 无法直接知道：

- 这是 `List<Video>`；还是
- 这是 `List<User>`。

这就是为什么 Jackson 只拿到 `List.class` 时，无法确定列表元素应该反序列化成什么类型。

可以简单理解为：

```text
Class<List>          只能表达：这是一个 List
TypeReference<List<Video>> 可以表达：这是一个元素类型为 Video 的 List
```

---

## 4. `TypeReference<T>` 是怎样保存泛型信息的？

使用时通常这样写：

```java
List<Video> videos = jsonUtils.fromJson(
        json,
        new TypeReference<List<Video>>() {}
);
```

需要注意最后的 `{}`：

```java
new TypeReference<List<Video>>() {}
```

这不是在创建普通的 `TypeReference` 对象，而是在创建一个匿名子类。

`TypeReference` 会通过这个匿名子类的父类声明，读取并保存完整的泛型类型：

```text
List<Video>
```

因此，Jackson 在运行时就能获得：

1. 外层类型是 `List`；
2. 列表元素类型是 `Video`。

然后它会把每一个 JSON 对象都转换成真正的 `Video` 对象，而不是普通的 `LinkedHashMap`。

---

## 5. 完整示例

假设 JSON 内容如下：

```json
[
  {
    "id": 1,
    "title": "Java 泛型讲解"
  },
  {
    "id": 2,
    "title": "Spring Boot 入门"
  }
]
```

### 错误或不规范的写法

```java
List<Video> videos = jsonUtils.fromJson(json, List.class);
```

这里传入的 `List.class` 没有包含 `Video` 类型信息。Jackson 很可能将每一个元素转换成：

```java
LinkedHashMap<String, Object>
```

而不是真正的 `Video`。

### 正确写法

```java
List<Video> videos = jsonUtils.fromJson(
        json,
        new TypeReference<List<Video>>() {}
);
```

此时返回结果中的每个元素都是 `Video` 对象：

```java
Video firstVideo = videos.get(0);
System.out.println(firstVideo.getTitle());
```

---

## 6. 更多常见用法

### 6.1 JSON 转 `List<String>`

```java
String json = "[\"Java\", \"Spring Boot\", \"Redis\"]";

List<String> names = jsonUtils.fromJson(
        json,
        new TypeReference<List<String>>() {}
);
```

### 6.2 JSON 转 `Map<String, Integer>`

```java
String json = "{\"likeCount\": 100, \"favoriteCount\": 20}";

Map<String, Integer> statistics = jsonUtils.fromJson(
        json,
        new TypeReference<Map<String, Integer>>() {}
);
```

### 6.3 JSON 转 `Map<String, Video>`

```java
Map<String, Video> videoMap = jsonUtils.fromJson(
        json,
        new TypeReference<Map<String, Video>>() {}
);
```

### 6.4 JSON 转多层嵌套泛型

```java
Map<String, List<Video>> categoryVideos = jsonUtils.fromJson(
        json,
        new TypeReference<Map<String, List<Video>>>() {}
);
```

`TypeReference` 可以保存完整的嵌套类型信息，因此即使泛型有多层，Jackson 也可以正确处理。

### 6.5 JSON 转自定义泛型对象

假设有一个统一分页对象：

```java
public class PageResult<T> {
    private Long total;
    private List<T> records;
}
```

反序列化时可以写：

```java
PageResult<Video> pageResult = jsonUtils.fromJson(
        json,
        new TypeReference<PageResult<Video>>() {}
);
```

这样 Jackson 才知道 `records` 中的元素应该转换为 `Video`。

---

## 7. 两个 `fromJson` 方法应该怎样选择？

### 普通对象：使用 `Class<T>`

```java
User user = jsonUtils.fromJson(json, User.class);
```

适用于：

- `User`
- `Video`
- `Category`
- `String`
- `Integer`

判断标准是：目标类型能否直接写成 `某类型.class`。

### 泛型对象：使用 `TypeReference<T>`

```java
List<Video> videos = jsonUtils.fromJson(
        json,
        new TypeReference<List<Video>>() {}
);
```

适用于：

- `List<Video>`
- `Set<Long>`
- `Map<String, User>`
- `PageResult<Video>`
- 多层嵌套泛型

判断标准是：目标类型是否需要保留尖括号 `<>` 中的信息。

---

## 8. 为什么方法本身还要声明 `<T>`？

方法声明如下：

```java
public <T> T fromJson(String json, TypeReference<T> typeReference)
```

各部分含义：

```text
<T>                       声明这是一个泛型方法
T                         方法返回值类型
TypeReference<T>          用参数携带目标类型信息
```

调用代码：

```java
List<Video> videos = jsonUtils.fromJson(
        json,
        new TypeReference<List<Video>>() {}
);
```

在这次调用中，`T` 就是：

```java
List<Video>
```

因此，整个方法在逻辑上相当于返回：

```java
List<Video>
```

泛型方法让调用者不需要手动进行强制类型转换。

---

## 9. 常见错误

### 错误一：泛型集合仍然传 `List.class`

```java
List<Video> videos = jsonUtils.fromJson(json, List.class);
```

虽然代码可能通过编译，但列表元素未必是 `Video`，可能是 `LinkedHashMap`。

应该改成：

```java
List<Video> videos = jsonUtils.fromJson(
        json,
        new TypeReference<List<Video>>() {}
);
```

### 错误二：忘记匿名子类的 `{}`

推荐写法：

```java
new TypeReference<List<Video>>() {}
```

最后的 `{}` 用来创建匿名子类，以便 `TypeReference` 捕获具体泛型信息。

### 错误三：把类型写得过于宽泛

```java
new TypeReference<List<Object>>() {}
```

这样 Jackson 仍然不知道业务上真正需要的元素类型。JSON 对象可能继续被解析为 `LinkedHashMap`。

如果明确需要 `Video`，应直接写：

```java
new TypeReference<List<Video>>() {}
```

---

## 10. 一句话总结

`TypeReference<T>` 的核心作用是：

> 在 Java 泛型类型擦除的情况下，帮助 Jackson 在运行时获得 `List<Video>`、`Map<String, User>` 等完整的泛型类型信息，从而正确完成 JSON 反序列化。

可以记住下面的选择规则：

```text
User、Video 等普通类型             → 使用 User.class、Video.class
List<Video>、Map<String, User> 等泛型类型 → 使用 new TypeReference<...>() {}
```
