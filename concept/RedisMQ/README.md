# Redis Streams (RedisMQ)

# 1. 등장 배경

## 등장 배경

Kafka나 RabbitMQ와 같은 MQ를 도입하고 운영하는 데는 별도의 인프라 구축과 학습 비용이 발생함.

- **기존 인프라의 활용**: 대부분의 서비스가 이미 캐싱 목적으로 Redis를 사용 중이므로, 추가 인프라 구축 없이 즉시 도입 가능함.
- **빠른 성능**: 인메모리(In-Memory) 기반으로 동작하여 속도가 매우 빠르고, 큐(Queue)의 형태를 훌륭하게 구현할 수 있음.

---

## 기존 Redis 자료구조의 한계

Redis는 원래 캐시 목적으로 설계되어 데이터를 읽고, 쓰고, 삭제하는 것에 최적화됨. 하지만 MQ는 **메시지 이력을 순서대로 보존하고 여러 Consumer가 같은 메시지를 읽을 수 있어야** 하는데, 기존 기능으로는 이를 만족하기 어려웠음.

- **List**: 읽으면(`pop`) 데이터가 사라짐. 여러 Consumer가 동시에 읽을 수 없고, 과거 이력 탐색 불가능.
- **Pub/Sub**: 수신측(Consumer)이 죽어있으면 발송된 메시지는 저장되지 않고 그대로 소멸(증발)됨.
- **Sorted Set**: Score 기준으로 순서가 변경될 수 있어 완벽한 순서 보장이 어렵고, 메시지를 기다리는 블로킹(Blocking) 불가.

---

## Disque 프로젝트

위 문제들을 인지하고 At-least-once(최소 한 번 배달 보장)를 목표로 개발했던 메시지 브로커

- **핵심 목표**: 시스템 장애가 발생하더라도 메시지가 절대 유실되지 않고 큐에서 최소 한 번은 처리되는 것을 보장함.
- **트레이드오프**: 목표 달성을 위해 메시지가 들어온 완벽한 순서를 보장하는 것은 포기하는 대신, 가용성을 챙기는 구조로 기획됨.

---

## Redis Streams

기존 시스템 안에서 시계열 데이터나 이벤트 메시지를 처리하기 위해, 타 MQ에서 사용하는 로그 기반의 추가 전용 방식(Append-only)을 Redis 메모리 내에 구현한 것이 **Streams**임.

### Append-only의 특징

- 기존 데이터를 건드리지 않고 새로운 데이터는 무조건 맨 끝에 이어 붙임.
- 수정 및 삭제 금지 (용량 관리를 위해 오래된 데이터 만료 처리만 가능), 오직 쓰기만 허용됨.
- 메시지는 시간 기반의 고유한 ID(Offset)를 통해 관리됨.

![](https://docs.kurrent.io/assets/event-log-is-append-only-B_7HQHxY.png)

---

## 5. Streams의 장점과 단점

### 장점

- **다중 컨슈머** : 데이터가 삭제되지 않으므로 여러 컨슈머가 동일한 데이터를 읽어갈 수 있음.
- **Replay** : 장애가 발생해도 남아있는 로그를 통해 과거 시점부터 다시 수행 가능함.
- **성능**: 중간 데이터 삽입/수정 없이 맨 뒤에 밀어 넣기만 하므로 쓰기 성능이 극대화됨.
- **Consumer Group 운영**: 여러 작업자가 메시지를 중복 없이 나누어 처리할 수 있으며, Redis가 그룹 내 누가 어디까지 읽었는지(Offset)를 기억해 줌.

### 단점 및 운영 고려사항

- **대용량 처리에 불리**: 철저한 인메모리 기반이므로 대규모 데이터 적재 시 한계가 명확함.
- **무한 보존 불가**: 메모리 한계(OOM 방지)로 인해 데이터 보존 길이에 제한(MAXLEN 등)을 두어야 함.
- **메시지 유실 가능성**: Redis 서버 자체가 다운될 경우, 적절한 AOF/RDB 백업 설정이 없다면 메모리 안의 메시지가 유실될 위험이 있음.

---

# 2. 작동 원리

## 물리적/논리적 저장 형태

![](https://redis.io/docs/latest/images/ri/consumer.png)

- **Append-Only Log**: 데이터의 중간 수정(UPDATE)이나 삭제(DELETE)가 불가능한 추가 전용 로그 형태. 새로운 메시지는 무조건 맨 뒤에만 추가되며, 한 번 들어오면 변하지 않는 불변성을 가짐.
    
    해시(Hash)와 유사하게 이름-값 쌍(Name-Value pairs)의 집합으로 구성. 하지만 모든 항목이 동일한 구조일 필요는 없음.
    
- **물리적 저장 (In-Memory + 하이브리드 최적화)**: 모든 메시지는 서버의 RAM(메모리)에 저장되어 빠름. 메모리 낭비를 막기 위해 개별 메시지를 띄워두지 않고 **매크로 노드(Macro-node)** 구조로 압축하여 저장함.

**Listpack**: Linked List의 포인터를 없애고, 메시지의 실제 데이터를 연속된 바이트 배열로 저장한 구조 

**Radix Tree (기수 트리)**: 시간 기반의 메시지 ID 특성을 이용, 공통된 타임 스탬프 접두사를 묶어 메모리를 절약하고 초고속 탐색을 지원하는 트리 구조
1526919030474-55 형태로 저장 1526919030474(밀리초) 55(밀리초 안에서의 순서)

---

## **통신 프로토콜**

- **RESP (REdis Serialization Protocol):** Redis가 클라이언트와 통신할 때 사용하는 TCP 기반의 자체 프로토콜.
- **특징:** 무거운 헤더나 메타데이터가 없고, 텍스트 기반으로 구조(첫 번째 글자로 데이터의 종류 구분, \r\n가 오면 데이터의 끝)가 단순해 컴퓨터의 파싱(Parsing) 속도가 빠름.
- **효과:** 인메모리 DB 특유의 빠른 데이터 처리 속도를 네트워크 통신 단에서 깎아먹지 않고, 애플리케이션과 브로커 간의 지연을 최소화함.

---

## Consumer Group와 메시지 관리

Redis는 메시지를 읽어갈 때 크게 두 가지 방식을 지원하며, 전문 MQ로서의 역할을 수행하기 위해 **Consumer Group** 개념을 도입함.

### Consumer Group

```bash
														┌───────────────────┐
														│    Redis Stream   │ (작업 100개)
														└─────────────────┬─┘
																			│
												 ┌────────────▼────────────┐
												 │      Consumer Group     │ (작업 분배)
												 └─┬─────────────────────┬─┘
					(1번, 3번 메시지) │                     │ (2번, 4번 메시지)
													 ▼                     ▼
										 [ Worker A ]           [ Worker B ]
                    (처리 후 XACK)          (처리 후 XACK)

						(Consumer Group을 통해 여러 Consumer가 메시지를 나눠 처리 가능)
```

- **정의**: Redis 서버 메모리 내부에 존재하는  상태 관리 객체. `XGROUP` 을 통해 생성
- **분산 처리** : 메시지 하나를 그룹 내 한 명의 워커만 가져가게 하여, 전체 시스템의 처리량을 수평적으로 확장.
- **처리의 신뢰성** : 워커가 처리 도중 죽더라도 Redis가 이를 기억(PEL)하여 데이터 유실을 방지.
- **유연한 팬아웃**: 다른 그룹끼리는 동일한 스트림 데이터를 각자의 진도에 맞춰 완전히 독립적으로 소비.

### 내부 구성 요소

- **Last-Delivered-ID (오프셋)**: 그룹 내 누군가에게 마지막으로 전달된 메시지의 ID. ("우리 그룹은 여기까지 읽었음"을 나타냄)
- **PEL (Pending Entries List)**: 워커에게 전달은 되었으나 아직 `XACK`(완료 신호)를 받지 못한 '처리 중' 상태의 메시지 목록.
- **Consumer State**: 그룹에 참여 중인 개별 워커들의 이름과, 각 워커가 현재 점유하고 있는 메시지 목록을 매핑한 정보.

![](https://velog.velcdn.com/images/nwactris/post/f061fff2-101d-4067-b1d5-443f7e981f70/image.png)

| 구분 | XREAD | XREADGROUP (Consumer Group) |
| --- | --- | --- |
| 대상 | 개별 Consumer가 직접 읽기 | Consumer Group 내에서 묶어서 읽기 |
| 메시지 분배 | 모든 Consumer가 동일한 메시지 수신 (Broadcast) | 그룹 내 Consumer들에게 나누어 분배 
(Load Balancing) |
| 상태 관리 | 없음 | 있음 (PEL 등록) |
| 처리 확인 | 불필요 | 필수 (XACK) |

---

## Producer → Consumer 흐름 및 Ack 처리

1. **발행 (Producer)**: `XADD` 명령으로 스트림 맨 뒤에 메시지를 기록하고 새로 생성된 고유 ID를 반환.  
대기 중인 컨슈머(`BLOCK` 옵션 사용)가 있다면 즉시 네트워크 버퍼로 데이터가 직접 푸시(Push)
2. **할당 및 보류 (PEL 등록)**: Consumer Group 내의 특정 컨슈머가 메시지를 읽어가면, 메시지는 큐에서 삭제되지 않고 해당 컨슈머의 PEL에 등록.
    - 이 상태에서는 다른 컨슈머가 같은 메시지를 중복으로 읽어갈 수 없음.
    - Redis 내부에 메시지 ID, 전달 시간, 전달 횟수 등이 기록.
3. **처리 완료 (Consumer)**: 컨슈머가 비즈니스 로직을 끝내고 `XACK` 명령을 보내면, 비로소 Redis가 PEL에서 메타데이터를 삭제하며 해당 메시지의 생애가 완전히 끝남.
    - *(주의: Redis에는 실패를 명시적으로 알리는 `NACK` 명령어가 없으며, `ACK`를 보내지 않는 것 자체를 실패나 지연으로 간주함.)*

## **메모리 관리 및 메시지 유실**

1. **메모리 관리 및 트리밍 (Trimming):** 데이터를 읽거나 `XACK`를 보내더라도 스트림 원본 데이터는 삭제되지 않고 계속 증가. 
    - 메모리 고갈을 막기 위해 `XTRIM` 명령어를 주기적으로 실행하여 오래된 항목을 지우거나 , 애초에 `XADD` 시점에 최대 길이를 제한하여(`MAXLEN` 을 통해 길이 제한) 새로운 항목 추가와 동시에 가장 오래된 항목을 밀어내는 전략을 사용
2. **유실 방지 및 복구:** 
    - `XPENDING` 을 통해 PEL을 조회 후 `*ACK*`을 못 받은 메시지 조회(I**dle Time**(마지막으로 전달된 후 경과된 시간)과 **Delivery Count**(전달 횟수)를 파악) 아래 정보를 가지고 옴
        - **메시지 ID (Message ID)**: 스트림에 저장된 메시지의 고유 식별자
        - **소비자 이름 (Consumer Name)**: 해당 메시지를 마지막으로 읽어간(소유권을 가진) 소비자의 이름
        - **대기 시간 (Idle Time)**: 해당 메시지가 소비자에게 마지막으로 전달된 후 현재까지 경과된 시간(밀리초 단위). 이 값을 통해 메시지가 얼마나 오랫동안 처리되지 않고 방치되었는지 판단
        - **전달 횟수 (Delivery Count)**: 해당 메시지가 소비자에게 전달된 총 횟수. 이 값이 특정 임계치를 넘으면 '독약 메시지(Poison Message)'로 판단하여 별도 처리
    - `XLAIM` 을 통해 오랫동안 처리 안된(Min-idle-time 이상) 메시지 소유권을 가지고 와서 재처리
    - `XAUTOCLAIM`은`XPENDING` + `XLAIM` 으로 일정 시간 이상 방치된 메시지들을 자동으로 찾아 소유권을 이전

---

# 3. 어느 도메인/분야에서 쓰이는가

## Redis Stream 적합한 경우

**1. 대규모 트래픽 버퍼링 및 비동기 분산 처리**
•  **핵심 로직:** 트래픽 폭주 시 애플리케이션의 병목을 막기 위해 로그나 이벤트를 빠르게 임시 적재하고, 뒷단의 여러 워커 서버(Consumer Group)가 부하를 나누어 병렬로 처리 .
• **적합 도메인:** 사용자 행동 로그 수집 파이프라인, 대규모 행사 시 푸시 알림 발송 대기열 등

**2. 미처리 메시지 기반 장애 복구형 작업 큐**
• **핵심 로직:** 무거운 작업 처리 중 워커 서버가 다운되더라도, 완료 도장(XACK)이 없는 하다가 만 작업(PEL)을 감지하여 데이터 유실 없이 다른 정상 서버가 안전하게 재시도 .
• **적합 도메인:** 외부 API(결제, 메일 발송) 연동 실패 재시도 로직 등

## Redis Stream **적합하지 않은 경우**

1. **수개월~수년 치의 데이터를 영구 보관해야 할 때 (빅데이터 적재):**
    - **이유:** 레디스의 데이터는 비싼 RAM에 저장. 하루에 수십 GB씩 쏟아지는 결제 로그를 레디스 스트림에 무한정 쌓아두면 순식간에 메모리가 가득 차서 서버가 다운됩니다.
2. **메시지 크기가 매우 큰 경우:**
    - **이유:** 고해상도 이미지 데이터 원본이나 메가바이트 단위의 JSON 문서를 통째로 레디스 큐에 밀어 넣으면 메모리 부족

## Redis Stream 사용 이유

1. **인프라 의존성**
    - **이유:** 이미 세션 관리나 DB 캐싱을 위해 레디스를 띄워놓고 있는 경우가 많음 따로 새로운 인프라를 추가할 필요 없이 기존에 있던 레디스를 사용
2. **지연 시간**
    - **이유:** 인메모리 기반이여서 아주 빠름
3. **운영 비용**
    - **이유:** 메시지를 오래 보관하면 AWS ElastiCache 비용 발생. 크기를 조절해서 저장하기 때문에 클라우드 비용과 전담 인력 비용 감소

# 4. 사용 방법

## 1. 환경 구성 (Docker)

```bash
# Redis 컨테이너 실행 (포트 6379 개방, 백그라운드 실행)
docker run --name my-redis -p 6379:6379 -d redis
```

## 2. Spring Boot 연동 및 필수 의존성

Redis Stream을 스프링에서 다루기 위해 `Spring Data Redis`를 사용

```bash
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
}
```

## 3. 발행(XADD) → 소비(XREAD)

### 1. 메시지 발행 (Producer)

`StringRedisTemplate`의 `opsForStream()`을 사용하면 Redis의 `XADD` 명령어와 동일하게 동작

```java
@Service
@RequiredArgsConstructor
public class StreamProducer {
    
    private final StringRedisTemplate redisTemplate;

    public void publishMessage(String streamKey, Map<String, String> message) {
        // 지정된 Stream 키에 Map 데이터를 XADD 명령어로 적재
        RecordId recordId = redisTemplate.opsForStream().add(streamKey, message);
        System.out.println("메시지 발행 완료 [Record ID: " + recordId + "]");
    }
}
```

### 2. 메시지 소비 (Consumer)

`StreamListener` 인터페이스를 구현하여 들어오는 메시지를 수신 (Redis의 `XREAD` 역할)

```java
@Component
public class StreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {
    
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        System.out.println("====== 새로운 메시지 수신 ======");
        System.out.println("Stream Key: " + message.getStream());
        System.out.println("Record ID: " + message.getId());
        System.out.println("Payload: " + message.getValue());
    }
}
```

### ③ 리스너 등록 (Configuration)

스프링이 백그라운드에서 지속적으로 Redis를 감시(`XREAD BLOCK`)하도록 컨테이너를 설정

```java
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final StreamListener<String, MapRecord<String, String, String>> streamListener;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer() {
        
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1)) // 1초간 Blocking Read (롱 폴링)
                        .serializer(new StringRedisSerializer()) // 직렬화 에러 방지를 위한 필수 설정
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(redisConnectionFactory, options);

        // "my-stream" 에서 가장 최신 메시지($)부터 읽도록 설정
        container.receive(StreamOffset.latest("my-stream"), streamListener);
        container.start();

        return container;
    }
}
```

# 5. 한계와 운영 고려사항

## 한계

1. 인메모리 DB이므로 Stream이 무한정 커질 수 없음
2. Consumer Group 내에서 여러 Consumer가 동시 처리 시 처리 완료 순서는 보장되지 않음
3. 중복 처리: Consumer 장애 시 메시지가 다른 Consumer에게 재전달될 수 있음
4. PEL 관리: ACK되지 않은 메시지는 PEL에 계속 남아 메모리 사용

## 메시지 유실을 막기 위한 방법

Redis Streams는 시스템 장애를 대비해서 3가지 방법을 사용 

1.  **PEL과 `XAUTOCLAIM` (컨슈머 장애 대응)**
- 컨슈머 서버가 죽어서 `XACK`를 보내지 못하면 메시지는 PEL에 방치됨. 살아있는 다른 컨슈머가 `XPENDING`으로 방치된 메시지를 찾아내고, `XAUTOCLAIM`으로 소유권을 뺏어와 대신 재처리함.
- `XAUTOCLAIM` 실행 시 `min-idle-time`(최소 방치 시간)을 설정해야 함.
    - 시간이 너무 **짧으면**: 살아있는 컨슈머가 정상 처리 중인 메시지까지 뺏어와 중복 처리가 발생할 위험이 있음.
    - 시간이 너무 **길면**: 장애가 발생한 메시지의 복구 및 재처리가 지연됨.
1.  **AOF / RDB 백업 (서버 재부팅 대응)**
- Redis 서버 자체가 다운될 경우 메모리 데이터가 증발하는 것을 막기 위해, 주기적으로 스냅샷을 찍거나(RDB) 쓰기 명령어 로그를 디스크에 영구 백업(AOF)하여 서버 재시동 시 복구함.
    
    
    | 특징 | RDB 스냅샷 | AOF(추가 전용 파일) |
    | --- | --- | --- |
    | 작동 방식 | 전체 데이터 세트의 주기적인 특정 시점 덤프 | 쓰기 명령이 실행될 때마다 로그를 남깁니다. |
    | 파일 형식 | 압축된 바이너리 .rdb파일 | 사람이 읽을 수 있는 명령 로그 |
    | 데이터 손실 위험 | 마지막 스냅샷 이후의 모든 쓰기 작업 | fsync 정책에 따라 다릅니다(없음~약 1초). |
    | 재시작 속도 | 빠름**.** 압축된 파일 하나만 쓱 읽어오면 됨 | 느림**.** 기록된 모든 명령어를 처음부터 끝까지 다시 실행해야 함 |
    | 디스크 I/O 영향 | 스냅샷 중에 높은 수치가 나타 | 작은 부하가 지속적으로 있음 |
    | 파일 크기 | 더 작은 (압축된) | 더 커짐 (재작성될 때까지 계속 커짐) |
    | ~에 가장 적합함 | 백업, 재해 복구, 클론 생성 | 최대 내구성, 감사 추적 |
    
    **RDB + AOF**
    평상시에는 데이터 유실을 막기 위해 AOF로 빠르게 로그를 기록. AOF 파일이 너무 커지면 백그라운드에서 이를 **RDB 스냅샷 형태의 바이너리로 꽉 압축**하여 파일의 앞부분에 배치하고, 그 이후의 로그만 텍스트로 이어 붙입니다.
    

## **추가 운영 고려사항**

1. **DLQ 직접 구현**
    
    DLQ: 계속 실패하는 메시지를 별도 공간으로 격리하는 패턴
    Redis Streams에는 내장된 Dead Letter Queue(DLQ)가 없음. 재시도를 반복해도 계속 실패하는 메시지는 `XAUTOCLAIM` 시 반환되는 `delivery-count`(전송 횟수)를 애플리케이션 단에서 체크하여, 임계값 초과 시 별도의 에러 스트림(DLQ 용도)으로 직접 옮겨주는 로직을 구현해야 함.
    
2. Redis cluster 구조에서 sharding 되어 있는 node에 메시지를 고르게 보내려면 추가적으로 N개의 stream을 각각의 node에 할당하도록 추가 개발이 필요

## 운영 중 모니터링해야 할 지표

- **`used_memory` (전체 메모리 사용량):** 스트림이 메모리를 다 먹어버리면 레디스 전체가 OOM(Out of Memory)으로 뻗고, 캐시 등 다른 서비스까지 연쇄 장애 발생
- **Stream Length (`XINFO STREAM`):** 특정 스트림에 쌓인 총 메시지 개수. 이 값이 꺾이지 않고 계속 우상향한다면 큐가 막히고 있다는 뜻
- **PEL Size (`XINFO GROUPS`):** Consumer에게 할당은 되었으나 아직 완료(`XACK`)되지 않은 대기 메시지의 수. 이 값이 급증하면 Consumer 서버들에 장애가 났거나 처리 로직에 병목(DB 락 등)이 발생

## 데이터 유실이 발생할 수 있는 시나리오

- **백업 지연**
가장 많이 쓰이는 AOF 백업 방식은 `appendfsync everysec` 옵션을 기본값으로 사용합니다. 즉, 쓰기 명령을 1초마다 모아서 디스크에 기록. 만약 명령어가 실행되고 디스크에 쓰이기 직전의 1초 사이에 서버가 크래시(다운)된다면, 그 1초 분량의 데이터는 복구할 수 없이 유실.
- **잘못된 XACK 처리:** 
데이터를 읽어오자마자(DB 저장 등 실제 비즈니스 로직을 끝내기도 전에) 레디스에 `XACK`를 먼저 보내버리면 문제가 발생. 이후 DB 인서트 과정에서 에러가 나더라도, 레디스 입장에서는 이미 해당 메시지가 성공적으로 처리되었다고 판단(`ACK` 수신)하여 PEL에서 영구 삭제.
- **성급한 Trimming:** 순간적으로 트래픽이 몰려 컨슈머가 서버들의 처리 속도를 따라가지 못하면 스트림에 데이터가 계속 쌓임. 아직 읽지 않은 데이터를 메모리 확보를 위해 삭제하면 데이터 유

# 참고자료

[gitbub - disque-module](https://github.com/antirez/disque-module)

[레디스 공식 유튜브 - Redis Streams](https://www.youtube.com/watch?v=Z8qcpXyMAiA)

[Antirez News](https://antirez.com/news/114)

[Redis 공식 문서](https://redis.io/docs/latest/develop/data-types/streams/)

[Stream 사용 방법 - 블로그](https://jybaek.tistory.com/935)

[Redis Stream 정리 - 블로그](https://velog.io/@yeongsang2/Redis-stream-%EC%A0%95%EB%A6%AC#22-entry-%EA%B5%AC%EC%A1%B0)

[Redis Stream 작용기 - G마켓](https://dev.gmarket.com/113)

[MQ 별 특징 3. - Redis Stream(2) - 블로그](https://velog.io/@nwactris/MQ-%EB%B3%84-%ED%8A%B9%EC%A7%95-3.-Redis-Stream2#%ED%99%95%EC%9E%A5%EC%84%B1-%EB%B0%8F-%EB%82%B4%EA%B2%B0%ED%95%A8%EC%84%B1)