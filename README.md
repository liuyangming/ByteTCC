ByteTCC是一个基于TCC（Try/Confirm/Cancel）事务补偿机制的分布式事务管理器，兼容JTA，因此可以很好的与EJB、Spring等容器（本文档下文说明中将以Spring容器为例）进行集成，支持Spring容器的声明式事务。

ByteTCC将TCC事务从逻辑上分为两个部分：TRY阶段、CC阶段（Confirm/Cancel）。每个阶段均由一个或多个service来构成，每个service均包含自己的业务逻辑。service在执行时，其操作由普通事务（LocalTransaction/XATransaction）来保证其原子性，即TCC事务基于普通事务来实现。

## 一、ByteTCC中TCC事务与普通事务的异同
#### 相同之处
* 1、均使用相同的相同的配置、相同的事务管理器；
* 2、均通过@Transactional注解来声明事务，在事务传播(propagation)、异常回滚等方面，二者均使用相同的语义；

#### 不同之处
* 1、使用TCC事务时，需要为service标注@Compensable注解，并指定confirm/cancel逻辑的service实现（非必须，如果没有补偿逻辑，也可不指定）；

## 二、关于幂等性
ByteTCC不要求service的实现逻辑具有幂等性。ByteTCC在补偿TCC事务时，虽然也可能会多次调用confirm/cancel方法，但是ByteTCC可以确保每个confirm/cancel方法仅被"执行并提交"一次，因此，使用ByteTCC时可以仅关注业务逻辑，而不必考虑事务相关的细节。

## 三、ByteTCC特性
* 1、支持Spring容器的声明式事务管理；
* 2、同时支持XA、TCC两种机制。应用开发者根据实际需要，可以单独使用XA事务或TCC事务，也可以同时使用XA、TCC两种机制的事务；
* 3、支持多数据源、多应用、多服务器事务传播的分布式事务协调，满足多种不同的事务处理的需求；
* 4、支持出错事务恢复。

## 四、当前版本
#### 0.2.1-SNAPSHOT主要目标
* 1、精简TCC事务管理器的处理逻辑；
* 2、新增对dubbo框架的支持。

## 五、已发布的历史版本
##### 0.1.2
* 地址：http://code.google.com/p/bytetcc

##### 0.2.0-alpha
* 地址：http://code.taobao.org/p/openjtcc
* 文档：http://code.taobao.org/p/openjtcc/wiki/index/

## 六、建议及改进
0.2.1-SNAPSHOT版本目前仍在开发中，若您有任何建议，可以通过1）加入qq群537445956向群主提出，或2）发送邮件至bytefox@126.com向我反馈。本人承诺，任何建议都将会被认真考虑，优秀的建议将会被采用，但不保证一定会在当前版本中实现。
