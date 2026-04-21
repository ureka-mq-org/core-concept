# Kafka

## Kafka는 왜 등장했을까?

Kafka는 왜 생겼을까요? 어떠한 문제를 해결하고 싶었기에 만들었을까요?

이를 알기 위해서는 당시 LinkedIn의 개발 팀장이었던 Jay Kreps가 겪었던 일을 볼 필요가 있겠습니다.

---

### 스파게티 아키텍처의 한계

2008년 링크드인은 급성장하고 있었습니다. 사용자가 기하급수적으로 늘어나다보니 단순 서비스에서 더 발전하고자 하는 회사의 방향성에 발 맞추어, 사용자의 활동 데이터, 예를 들면 클릭 이벤트와 어떤 페이지를 보는지를 기반으로 추천 시스템, 검색 랭킹에 반영하고자 했습니다. 그런데 여기서 문제가 있었습니다.

- P2P 연결의 문제 - 서비스가 늘어날 때마다 데이터 소스(DB, 로그)와 데이터를 받는 곳(Hadoop, 모니터링 시스템)을 p2p로 연결을 했습니다(듣기만 해도 미친 짓이긴 합니다). 그러니 시스템이 조금만 복잡해져도 아키텍처가 꼬이고 엉키는 현상이 발생했습니다.
- 기존에 사용하던 MQ의 한계(ActiveMQ 등) - 당시에 존재하던 MQ로는 초당 수백만 건씩 쏟아지는 링크드인의 대규모 이벤트 데이터를 처리하기에는 너무 무거웠습니다.
- 배치 처리의 한계 - 로그 데이터를 수집해 Hadoop에 넣는 방식은 시간이 너무 오래 걸려, 지금 당장 사용자에게 맞는 추천을 제공하는 실시간성을 충족하지 못했습니다.

---

### 모든 일은 로그로 부터 온다

Reference로 Jay Kreps가 로그를 어떻게 바라보는지를 올렸습니다. 그는 로그의 중요성을 매우 높게 보았습니다. 그러면서 동시에 생각한 것이 서비스에서 발생하는 모든 이벤트를 시간 순서대로 쌓아두면 어떨까?라는 생각을 하게 됩니다.

- 생성자(Producer)는 그저 로그 끝에 데이터를 붙이기만 하고
- 소비자(Consumer)는 각자 자기가 있을 위치(Offset)를 기억하며 로그를 읽는다.

그런데 이 방식이 단순하면서 병목이 적어 엄청난 Throughput을 견딜 수 있게 되었습니다.

---

### Kafka 이름의 유래

Jay Kreps가 Franz Kafka라는 작가를 좋아했다고 합니다. 그래서 Kafka도 **쓰기에 최적화**된 시스템이 되길 바란다면서 kafka라고 이름을 붙였다고 하네요.

> 그래서 Kafka는 p2p로 꼬여있던 아키텍처와 로그 시스템을, 중앙 집중형인 MQ를 통해 로그를 순차 처리할 수 있도록 하여 처리량을 높이고, 당연히 로그 데이터이니 저장을 할 수 있으며, 과거 데이터를 다시 볼 수 있도록 하기 위해 등장했습니다.
> 

## Kafka는 어떻게 동작할까?

### 아키텍처

#### Segment

Kafka partition 내의 데이터를 저장하는 기본 단위입니다. 일정 시간이 지나거나 특정 크기가 되면 새로운 segment 파일로 전환된다고 합니다. 가장 작은 단위라고 말할 수 있을 것이고 I/O 작업을 최적화하고 데이터 순차 읽기 쓰기를 보장하는 요소입니다.

---

#### Topic & Partition

데이터 분산과 병렬 처리의 기본 단위입니다. 1개의 토픽 안에 여러 파티션이 있을 수 있고, 파티션이 늘어날수록 처리량이 증가하는 메커니즘입니다.

![image.png](Kafka/image.png)

파티션 및 토픽이 브로커 안에 있는 것을 보실 수 있습니다. 파티션은 큐를 나눠서 병렬 처리를 가능하게 하는 기본 단위입니다. 또한 이 안에는 순서를 보장하는 로그 구조를 가지고 있습니다. 이 구조 덕에 각 메시지는 파티션 내에서 고유한 오프셋 값을 가지고, 이를 이용해 메시지의 위치를 정확하게 식별하도록 합니다.

| Offset | Message |
| --- | --- |
| 0 | Message A |
| 1 | Message B |
| 2 | Message C |
| 3 | Message D |
| 4 | Message E |

이 오프셋은 0-base index이고 들어온 순서를 나타냅니다. 즉 Consumer가 데이터를 순서대로 처리할 수 있도록 하고, 어떤 메시지에 offset을 통해 문제가 생긴 메시지 위치를 식별하고 분석할 수 있게 해줍니다.

![image.png](Kafka/image%201.png)

또한 각 파티션 내에는 한 개의 리더가 존재하고, 모든 읽기와 쓰기 연산은 이 리더를 통해 이루어집니다. 팔로워는 복제본으로 리더의 데이터를 복제하여 시스템의 가용성을 높이고, 리더에 문제가 생기면 승격이 가능합니다.

또한 kafka는 키 선택이 매우 중요합니다. 이 키가 어느 파티션에 분산 저장이 될지 결정하기 때문입니다.

---

#### Producer & Consumer Group

Producer는 데이터를 kafka 클러스터로 전송하는 역할을 하는 클라이언트 애플리케이션입니다. kafka 토픽의 특정 파티션으로 메시지를 전송하고, 이를 통해 실시간 데이터 스트리밍이나 로깅 시스템에 활용합니다.
다양한 설정이 존재합니다. 메시지 배치 전송, 압축, 재시도 등의 메커니즘이 이에 해당하고 data push를 진행합니다.

또한 메시지를 전송할 때 어떤 파티션에 보낼지는 key의 유무에 따라 달라집니다.

- key가 있을 때에는 key의 해시값을 이용해 항상 특정 파티션으로 전송합니다.
- 없을 때에는 RR, Sticky와 같은 전략을 사용합니다.

Consumer는 클러스터에서 데이터를 읽는 역할을 합니다. consumer 그룹의 경우는 동일한 group id를 공유하고 파티션을 나누어 가집니다. 하나의 파티션은 오직 한 명의 consumer에만 연결이 되고, 그룹 내 새로운 consumer가 들어오거나 나가면 group coordinator가 파티션 소유권을 다시 분배합니다.
그룹을 통해 파티션의 데이터를 독립적으로 처리할 수 있도록 하는데 이는 전체 시스템의 병렬 처리 능력과 효율성을 향상시켜 줍니다.

---

#### Broker & Cluster

![image.png](Kafka/image%202.png)

Cluster는 어찌보면 카프카의 가장 큰 단위입니다. 그 안에 여러 브로커가 존재하고, 브로커 하나가 죽으면 다른 브로커를 통해 이벤트를 받으면서 고가용성을 유지합니다.

Broker에도 리더와 팔로워가 존재합니다. 그런데 리더가 이제 실패를 하게 되면 ISR(현재 리더와 동기화된 팔로워 브로커)에서 하나를 리더로 승격시킵니다.

그리고 브로커는 토픽 내의 파티션 데이터를 서로 복제하게 하고, 리더 파티션은 읽기와 쓰기, 팔로워는 리더의 데이터를 복제하게 하는 그것을 관리하는 것이 브로커입니다.

---

### 동작 방식

![image.png](Kafka/image%203.png)

kafka 공식 홈페이지에 나온 내용을 가지고 설명을 해볼까 합니다.

1. Partition을 통한 병렬 처리 및 수평 확장
    1. Topic은 논리적인 개념입니다. 음..라벨을 생각하면 될 것 같아요. 실제 데이터는 파티션에 저장이 됩니다.
    2. 파티션이 위 그림에 4개가 있습니다. 병목이 생기지 않도록 여러 파티션을 만들고 처리량을 늘립니다. 그리고 같은 키를 가진 경우에는 같은 파티션에 데이터를 저장할 수 있습니다.

또한 rebalancing이라는 것이 존재합니다. 이는 특정 토픽을 구독하던 Consumer Group에 변동 사항이 발생했을 때 해당 Group 안에서 파티션을 재분배하는 행위를 의미합니다.

하나의 파티션은 반드시 하나의 컨슈머에게 할당되어야합니다.

![image.png](Kafka/image%204.png)

위 상태에서 consumer가 추가된다면 아래와 같습니다.

![image.png](Kafka/image%205.png)

이 때 파티션 분배 모드가 EAGER이라면 해당 consumer group 내부의 모든 consumer는 기존 파티션 구독 정보 전부 버리고 새로운 파티션 분배를 받습니다.

---

### 메시지 전송 방식

메시지 전송에는 크게 3가지가 존재합니다.

1. at-least-once (기본 방식)
    
    중복은 발생할 수 있지만, 유실은 없습니다.
    
    ![image.png](Kafka/image%206.png)
    
    - 프로듀서가 브로커의 특정 토픽으로 메시지 A를 전송한다.
    - 브로커는 잘 받았다는 의미로 ACK를 프로듀서에게 응답한다.
    - 브로커의 ACK를 받은 프로듀서는 다음 메시지 B를 브로커에게 전송한다.
    - **브로커는 메시지를 받았지만** 네트워크 오류나 브로커 장애로 인해 **결국 ACK가 프로듀서에게 전달되지 않는다.**
    - 메시지 B의 **ACK를 받지 못한 프로듀서는** 브로커가 메시지 B를 받지 못했다고 판단해 **메시지 B를 재 전송** 한다.
    
2. at-most-once
    
    유실될 수 있지만 중복은 없습니다.
    
    ![image.png](Kafka/image%207.png)
    
    - 프로듀서가 브로커의 특정 토픽으로 메시지 A를 전송한다.
    - 브로커는 잘 받았다는 의미로 ACK를 프로듀서에게 응답한다.
    - 브로커의 ACK를 받은 프로듀서는 다음 메시지 B를 브로커에게 전송한다.
    - 브로커는 메시지를 받았지만 네트워크 오류나 브로커 장애로 인해 결국 ACK가 프로듀서에게 전달되지 않는다.
    - **프로듀서는 브로커가 받았다고 가정하고 메시지 C를 전송한다.**
    
3. exactly-once
    
    유실도 중복도 없습니다.
    
    먼저 이 개념을 보기 위해서는 중복없는 전송, 멱등성 프로듀서를 알아야합니다.
    
    ![image.png](Kafka/image%208.png)
    
- 프로듀서가 브로커의 특정 토픽으로 메시지 A를 전송한다. 이때 **PID(Producer ID)와 메시지의 시퀀스 번호** 0을 같이 전달한다.
- 브로커는 메시지를 저장하고 PID와 시퀀스 번호를 기록해둔다. 그리고 ACK를 프로듀서에게 응답한다.
- 브로커의 ACK를 받은 프로듀서는 다음 메시지 B를 브로커에게 전송한다. PID는 동일하지만 시퀀스 번호는 증가한 1 값을 보낸다.
- 브로커는 메시지를 저장하고 PID와 시퀀스 번호를 기록해둔다. 하지만 네트워크 오류나 브로커 장애로 인해 **결국 ACK가 프로듀서에게 전달되지 않는다.**
- 메시지 B의 ACK를 받지 못한 **프로듀서는 브로커가 메시지 B를 받지 못했다고 판단해 메시지 B를 재 전송**한다.

**브로커는 전달받은 데이터로부터 PID와 시퀀스 번호를 확인하여 메시지 B가 이미 저장된 것을 확인**하고 중복 저장하지 않고 ACK 응답만 보냅니다. 결국 메시지의 중복 저장을 피할 수 있게 합니다.

---

또한 kafka는 pull 방식을 사용합니다. push 방식은 broker의 속도에 의존하게 되지만 pull방식을 채택하여 복잡한 피드백이나 요구사항이 사라지게 하였고 더 간단하고 편리하게 클라이언트를 구현하도록 하였습니다.

## Kafka는 어떻게 사용할까?

#### 1. 프로젝트 설정(`build.gradle)`

```yaml
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'com.fasterxml.jackson.core:jackson-databind' // JSON 직렬화용
}
```

#### 2. message 객체 정의

```java
public record OrderEvent(
    String orderId,
    String productId,
    int quantity,
    long timestamp
) {}
```

#### 3. Producer

```java
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    private static final String TOPIC = "order-topic";

    public void sendOrderEvent(OrderEvent event) {
        // 비동기 전송 및 콜백 처리
        kafkaTemplate.send(TOPIC, event.orderId(), event)
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    System.out.println("전송 성공: " + result.getRecordMetadata().offset());
                } else {
                    System.err.println("전송 실패: " + ex.getMessage());
                }
            });
    }
}
```

#### 4. Consumer

```java
@Service
public class KafkaConsumerService {

    @KafkaListener(topics = "order-topic", groupId = "group-01")
    public void consume(OrderEvent event) {
        System.out.println("메시지 수신 성공!");
        System.out.println("주문 ID: " + event.orderId());
        System.out.println("상품명: " + event.productId());
    }
}
```

#### 5. `application.yml`

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: group-01
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*" # 모든 패키지의 JSON 역직렬화 허용
```

동일한 group-id 를 가진 consumer들은 토픽의 데이터를 나누어서 처리하여 부하를 분산합니다.

## Kafka는 어느 분야에서 사용될까?

### Event Driven System(Architecture)이란?

event는 데이터의 변경, 생성, 삭제 등 서비스의 변화를 의미합니다. event가 발생하면 stream을 통해 이벤트가 전달되고 해당 이벤트가 필요한 서비스들이 빠르게 이벤트를 캐치해서 이를 기반으로 동작하는 것이 바로 EDS입니다.

![image.png](Kafka/image%209.png)

![image.png](Kafka/image%2010.png)

예를 들어 특정 user가 특정 소프트웨어를 Favorite List에 추가하는 이벤트가 발생했다고 한다면, gom이라는 유저가 kafka를 리스트에 추가했는지 안 했는지 아려면 query를 실행하여 DB에 데이터를 직접 접근하게 됩니다.

```java
SELECT 1 FROM User_Favorite WHERE user_id = "gom" AND software_id IN ("kafka");
```

Favorite List에 software를 추가하는 이벤트 발생 시 DB에서 outbound Event가 발생하지 않는다면

**Event의 발생을 DB에 대한 정보가 바뀐 것을 통해서 알기 때문**에 **발생한 Event를 처리하는 개념이 아닙니다.** (라고 합니다 ㅎㅎ)

기존의 시스템의 경우에는 시스템 A가 시스템 B를 호출하는 Request-Response였다면, kafka는 어떤 일(event)가 일어났다는 것을 알리는 방식입니다.

**기존 방식**

- **강한 결합(Tight Coupling):** A 시스템이 호출한 B 시스템이 응답하지 않으면 A도 멈춰버립니다.
- **확장성 저하:** 새로운 기능(C 시스템)을 추가하려면 A의 코드를 고쳐서 B와 C 모두를 호출하게 만들어야 합니다.

**EDA**

- **느슨한 결합(Loose Coupling):** A는 "주문이 들어왔음"이라는 이벤트만 발행(Publish)하고 자기 할 일을 합니다.
- **유연한 확장:** 결제, 배송, 알림 시스템이 각자 카프카에 붙어서 그 이벤트를 가져가 처리합니다. 새로운 '포인트 적립' 서비스가 추가되어도 기존 시스템은 건드릴 필요가 없습니다.

---

그래서 kafka는 어떤 곳에서 주로 사용될까요?

보통은 MSA, 로그 수집, 스트리밍, 이벤트 소싱, 데이터 파이프라인에 사용됩니다. 실제 기업에서 사용한 예시를 좀 가져와 보겠습니다.

#### Netflix

**초기 단계**

![image.png](Kafka/image%2011.png)

가장 초기에는 로그를 Hadoop에 저장하는 구조였습니다.

서버에서 발생하는 로그들을 chukwa라는 수집기를 통해 s3에 저장하고, 빅데이터 플랫폼 팀이 s3파일을 가지고 작업을 하고 hive에 작성을 했습니다. 이 경우 end to end로 최대 10분이 걸렸다고 합니다.

---

**과도기**

그런데 시간이 지나고 kafka와 Elasticsearch가 등장하면서 real-time 분석에 대한 요구가 생겨났고 여기에 kafka를 도입합니다.

![image.png](Kafka/image%2012.png)

Chukwa는 로그를 S3로 보내는 동시에 Kafka로도 데이터를 전달할 수 있었고, 당시 기준으로 전체 이벤트의 약 30% 정도가 실시간 파이프라인으로 전달되었습니다.

이 실시간 구조의 핵심은 Router였습니다. Router는 Kafka 토픽의 데이터를 읽어 목적지에 따라 다시 전달하는 역할을 수행했습니다. 예를 들어 Elasticsearch로 보내 실시간 검색과 모니터링에 활용하거나, 다른 Kafka 토픽으로 전달해 추가 스트림 처리를 가능하게 했습니다.

이를 통해 사용자들은 Spark, Mantis, 자체 애플리케이션 등 다양한 도구를 활용하여 실시간 데이터 처리를 할 수 있게 되었고, 기존 배치 처리 구조보다 훨씬 빠른 데이터 활용이 가능해졌습니다.

하지만 운영 과정에서 한계도 있었습니다. Kafka Consumer가 일부 파티션 소비를 멈추거나, 배포 중 리밸런싱 문제로 비정상 상태에 빠지는 경우가 있었고, 수백 개의 라우팅 작업을 여러 클러스터로 나누어 관리해야 해서 운영 부담도 점점 커졌습니다.

결국 Netflix는 단순히 데이터를 전달하는 수준을 넘어, 대규모 실시간 데이터 흐름을 안정적으로 운영할 수 있는 새로운 플랫폼의 필요성을 느끼게 되었습니다.

---

**완성단계**

![image.png](Kafka/image%2013.png)

넷플릭스는 또한 아키텍처를 단순화하고자 했습니다. 위 그림은 당시 게시글 기준 최신본입니다.(아마 지금은 바꼈을 것 같긴 합니다) 이 경우 크게 3가지의 핵심 구성이 존재합니다.

첫 번째는 데이터 수집(Data Ingestion) 입니다. 애플리케이션은 두 가지 방식으로 데이터를 적재할 수 있었습니다. 하나는 Netflix에서 제공하는 Java 라이브러리를 사용해 Kafka로 직접 전송하는 방식이고, 다른 하나는 HTTP Proxy로 데이터를 보내면 Proxy가 대신 Kafka에 기록하는 방식입니다.

두 번째는 데이터 버퍼링(Data Buffering) 입니다. Kafka는 복제 기능을 갖춘 영속적 메시지 큐(Persistent Message Queue)로 동작하며, 데이터를 안정적으로 저장합니다. 또한 S3나 Elasticsearch 같은 하위 시스템에 일시적인 장애가 발생하더라도 Kafka가 중간 버퍼 역할을 하면서 전체 파이프라인 장애를 완화할 수 있습니다.

세 번째는 데이터 라우팅(Data Routing) 입니다. 라우팅 서비스는 Kafka에 들어온 데이터를 각 목적지로 전달합니다. 주요 목적지는 S3, Elasticsearch, 그리고 Secondary Kafka 입니다. 즉, 하나의 데이터 소스를 여러 시스템에서 동시에 활용할 수 있도록 연결해주는 역할입니다.

---

#### Uber

이번에는 uber가 기존에 사용하던 kafka를 더 개선한 내용을 작성하고자 합니다.

![image.png](Kafka/image%2014.png)

위 이미지는 기존 uber에서 사용하던 아키텍처입니다. 애초에 uber는 수천개의 마이크로서비스를 운영하며 매일 엄청난 양의 비동기작업을 처리했습니다. 다만 여러 문제가 있었습니다.

**문제점**

우버는 수천 개의 마이크로서비스를 운영하며 매일 엄청난 양의 비동기 작업(이메일 발송, 요금 계산, 푸시 알람 등)을 처리해야 했습니다. 하지만 표준적인 카프카 컨슈머 방식에는 몇 가지 치명적인 병목이 있었습니다.

1. HOL blocking

카프카는 파티션 내의 메시지 순서를 보장하기 위해 **순차적으로 처리**합니다. 만약 특정 파티션의 메시지 하나가 처리가 매우 늦어지면(예: 외부 API 타임아웃), 해당 파티션에 쌓인 뒤쪽 메시지들은 앞의 작업이 끝날 때까지 모두 대기해야 합니다.

1. Consumer 관리 복잡성 및 리소스 낭비

수천 개의 서비스가 각각 카프카 클라이언트 라이브러리를 포함해야 했습니다. 이는 라이브러리 업데이트, 설정 관리, 그리고 각 서비스가 브로커와 유지해야 하는 커넥션 수의 폭발적인 증가를 야기했습니다.

1. Rebalancing의 문제

컨슈머 그룹에 새로운 인스턴스가 추가되거나 죽을 때 발생하는 리밸런싱은 전체 시스템의 일시적인 중단을 가져옵니다. 우버와 같은 대규모 클러스터에서는 이 리밸런싱이 너무 자주, 그리고 오래 걸리는 문제가 있었습니다.

---

**해결책 Counsumer Proxy의 도입**

![image.png](Kafka/image%2015.png)

우버는 한계 극복을 위해 kafka와 msa 사이에 consumer proxy를 추가합니다.

**동작 원리**

1. proxy가 메시지를 수집합니다. 서비스가 직접 Kafka를 구독하는 것이 아닌, Golang으로 작성된 consumer proxy가 kafka로부터 데이터를 가져옵니다.
2. push 기반으로 프록시는 가져온 메시지를 각 ms에 http, or grpc를 이용해 전달합니다.
3. 순서 보장을 해제합니다. 비동기 작업 큐의 특성상 순서가 엄격하게 중요하지 않았기에 프록시는 1번 처리 중에도 2번, 3번 메시지를 가용한 서비스 인스턴스에 병렬로 던집니다.

---

그 결과로 아래와 같은 이점을 얻었습니다.

**병렬 처리 극대화**

더 이상 특정 메시지 하나 때문에 파티션 전체가 멈추지 않습니다. 프록시가 여러 메시지를 동시에 여러 인스턴스로 전달하므로 전체 시스템의 Throughput(처리량)이 비약적으로 상승했습니다.

**운영 효율성**

개발자들은 이제 자신의 서비스 코드에 복잡한 카프카 설정을 넣을 필요가 없습니다. 단순히 특정 엔드포인트(HTTP/gRPC)로 들어오는 요청을 처리하기만 하면 됩니다. 카프카의 복잡한 로직은 프록시 레이어에서 전담하기 때문입니다.

**유연한 재시도 및 DLQ 관리**

프록시 레이어에서 메시지 처리 실패 시의 재시도 전략(Retry Policy)과 데드 레터 큐(DLQ) 처리를 중앙 집중식으로 관리할 수 있게 되었습니다.

## Kafka의 한계와 고려사항은 무엇일까?

### kafka의 약점과 제약

- kafka는 운영 난이도가 높기로 유명하다고 합니다. 주키퍼, KRaft모드 관리, Config, 클러스터 확장 시 리밸런싱 비용 등..운영에 신경 쓸 요소들이 수백 개에 달한다고 합니다.
- 파티션 수 조정의 비가역성이 존재합니다. 파티션을 늘릴 수 있지만 줄이는 것이 불가능해서 파일 핸들러 과부화, 복제 지연 등의 문제가 발생할 수 있기에 설계단계부터 신중하게 접근해야합니다.
- 메시지의 크기가 크면 네트워크 대역폭과 브로커 메모리에 심각한 부하를 줍니다. 그렇기에 메시지는 1MB 이하, 그리고 큰 데이터는 s3에 올리고 경로만 공유하는 것이 좋다 합니다.
- 중복 방지, 순서 보장 등을 완벽히 하려면 컨슈머 로직이 매우 복잡해집니다. 단순히 가져다 쓴다의 수준이 아닙니다.

---

### 운영 중 어떤 것을 모니터링 해야할까?

- **Consumer Lag:** 프로듀서의 생산 속도와 컨슈머의 소비 속도 차이. (가장 중요한 병목 지표)
- **Under-Replicated Partitions (URP):** 리더의 데이터가 팔로워에게 제대로 복제되지 않는 파티션 수. (데이터 유실의 전조 증상)
- **Active Controller Count:** 클러스터 내 리더 선출을 담당하는 컨트롤러가 1인지 확인. (0이면 클러스터 마비)
- **Disk Usage:** 디스크가 100% 차면 브로커는 즉시 셧다운됩니다. 80% 선에서 알람이 필수입니다.

---

### 메시지 적체 및 Consumer 지연에 어떻게 대응할까?

가장 쉬운 방법들은 먼저 4가지입니다.

1. Partition, Consumer 수평 확장(scale out)
    
    이 방법은 partition 수가 consumer 수 이상이어야 효과가 있으나 partition 수는 줄일 수 없다는 것이 문제입니다.
    
2. consumer 내부 병렬화
3. consumer 로직 최적화 및 배치 사이즈 조절
4. DLQ 활용

그런데 위에서 끝나는 것이 아니라 실제 기업에서는 다른 방식도 사용합니다.

네이버의 경우 **Parallel Consumer**를 도입하였고 앞서 언급했듯 uber의 경우는 자신들이 proxy를 만들어서 이를 처리하고자 했습니다. 간략하게 2번 요소와 parallel consumer의 차이를 언급해드리겠습니다.

---

**Consumer 내부 병렬화**

개발자가 직접 `poll()`로 메시지를 대량으로 가져온 뒤, `ThreadPoolExecutor` 같은 곳에 던지는 방식입니다.

- **동작:** `Consumer.poll()` → `List<Record>` 획득 → 스레드 풀에 작업 할당.
- **치명적 단점 (Offset Hell):** 1번부터 10번 메시지를 10개 스레드에 던졌는데, 5번 메시지가 실패하고 10번 메시지가 먼저 성공했다면? 10번 오프셋을 커밋하는 순간 5번 데이터는 **유실**됩니다.
- **순서 보장:** 파티션 내에서의 순서를 지키기가 매우 어렵습니다. (직접 로직을 짜야 함)

---

**Parallel Consumer**

Parallel Consumer는 단순한 오프셋 커밋이 아니라, **각 메시지 단위의 성공 여부를 추적(Tracking)**합니다.

- **개별 메시지 추적:** 1~10번 중 5번이 실패하고 나머지가 성공했다면, 카프카 브로커에는 10번까지 완료되었다고 보내지 않고 **"중간에 이빨이 빠졌다"**는 정보를 메타데이터 형식으로 관리합니다.
- **Key 기반 병렬 처리:** 파티션이 하나라도 메시지 **Key가 다르면 병렬로 처리**하고, **같은 Key면 순서를 보장**하며 처리하는 지능적인 스케줄링을 제공합니다.
- **Back-pressure 제어:** 컨슈머의 처리 속도가 너무 느려지면 `poll()` 호출 속도를 자동으로 조절하여 메모리 과부하를 막습니다.

#### 요약

| 구분 | 수동 멀티스레딩(내부 병렬화) | Parallel Consumer(Library) |
| --- | --- | --- |
| 러닝커브 | 낮음(직접 구현) | 중간(라이브러리 학습 필요) |
| Offset Commit | 불안정(유실/중복 위험) | 매우 정교함(개별 메시지 추적) |
| 순서 보장 | 거의 불가능(직접 로직 작성) | Key 단위 순서 보장 가능 |
| 적용 시점 | 순서가 중요하지 않은 단순 작업 | 대규모 병렬 처리 + 신뢰성 필요 실무 |

---

#### Reference(를 빙자한 읽어보면 좋을만한 글들)

https://engineering.linkedin.com/distributed-systems/log-what-every-software-engineer-should-know-about-real-time-datas-unifying

https://medium.com/sjk5766/%EC%B9%B4%ED%94%84%EC%B9%B4-%EB%A9%94%EC%8B%9C%EC%A7%80-%EC%A0%84%EC%86%A1-%EB%B0%A9%EC%8B%9D-4b4ca288cca6

https://www.linkedin.com/pulse/how-netflix-uses-apache-kafka-deliver-seamless-streaming-kushal-sinha-nu4wf/

https://d2.naver.com/helloworld/9581727

https://devocean.sk.com/community/detail.do?ID=165478&boardType=DEVOCEAN_STUDY&page=1

https://netflixtechblog.com/evolution-of-the-netflix-data-pipeline-da246ca36905

https://www.uber.com/kr/en/blog/kafka-async-queuing-with-consumer-proxy/

https://d2.naver.com/helloworld/7181840