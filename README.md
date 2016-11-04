ByteTCC是一个基于TCC（Try/Confirm/Cancel）事务补偿机制的分布式事务管理器，兼容JTA，可以很好的与EJB、Spring等容器（本文档下文说明中将以Spring容器为例）进行集成，支持Spring容器的声明式事务。

ByteTCC将TCC事务从逻辑上分为两个阶段：TRY阶段、CC阶段（Confirm/Cancel）。每个阶段均由一个或多个service来构成，每个service均包含自己的业务逻辑。service在执行时，其操作由本地事务（LocalTransaction）来保证其原子性，即TCC事务基于本地事务来实现。

## 一、ByteTCC对Try/Confirm/Cancel各阶段职责的规划
一般认为，Try阶段适用于对业务数据执行校验并预留出资源；Confirm阶段在Try阶段预留的资源上执行真正的业务操作；Cancel则用于释放Try阶段预留出的资源。

然而，上述原则并不能适用于所有的场景。

为什么这么说呢？TCC事务中，Try阶段是由业务直接调用的，而Confirm/Cancel则是由TCC事务管理器触发调用的，因此，
* 1、如果Try阶段仅做校验和预留资源，而将真正的业务操作放在Confirm阶段执行，那么，一旦业务执行（Confirm阶段）出错，就会使得i)该错误不能被业务程序（调用方）感知并处理；ii)TCC事务管理器也没有针对Confirm操作错误的处理机制，故保障性低。
* 2、相反，如果真正的业务操作在Try阶段执行，在业务执行出错时业务程序（调用方）仍然可以获得对其进行处理的机会，即使不便处理或者处理失败的情况下后续还有Confirm/Cancel操作对Try阶段的操作进行补充/补偿，故保障性高；

正因如此，ByteTCC更倾向于认为：Try阶段才是TCC事务最关键的阶段，而Confirm阶段仅是Try阶段的一个辅助和补充（非必需，需要时才使用）。任何重要的操作，只要不会导致事务出现不一致性的可能，都应该尽可能的在Try阶段执行。

#### 1.1、Try阶段
一般情况下，只要在Try阶段执行不会导致事务出现不一致性可能的操作，都应该尽量放在Try阶段完成。当然，如果某些操作并不太适合做业务补偿，也可以将其放在confirm阶段。
导致事务出现不一致性可能的操作包括（但不限于）：多个资源参与事务，如跨库操作，发送消息等。

#### 1.2、Confirm阶段
Confirm阶段为Try阶段的补充，一些在Try阶段执行会导致事务不一致的操作，才放到这个阶段来执行。例如，发送消息、与Try阶段写操作不属于同一个LocalTransaction的写操作等。

Confirm阶段是一个辅助而非必需的阶段。若一个事务只用Try阶段就能很好的解决问题，就没必要将业务拆分成Try和Confirm两个阶段来执行。TCC机制提供一个Confirm的机制只是为了保障存在多个资源参与事务情况下可以将分布式事务分成几个阶段处理，这并不意味着任何业务逻辑都需要有Confirm逻辑（只有一个资源参与的事务，刻意的拆分出Try/Confirm两个阶段反而将业务逻辑复杂化），因此Confirm阶段是可选的一个阶段。没有Confirm阶段的TCC机制，即与事务补偿机制相同。

#### 1.3、Cancel阶段
Cancel阶段的操作主要是用于对Try阶段的执行结果进行补偿，该阶段执行的存在多个资源参与事务已经被可以将分布式事务分成几个阶段处理了影响（Try阶段的LocalTransaction已经提交了）。
Cancel补偿逻辑是业务必须提供的，但并不意味着Cancel阶段一定会执行该补偿逻辑。如果Try阶段虽然被调用但是其所在的LocalTransaction被TCC事务管理器回滚了，则Cancel阶段的补偿操作可以不必执行。

## 二、ByteTCC为什么要基于本地事务实现TCC全局事务？
TCC各阶段均有业务service构成，而业务service对数据的修改又由本地事务来控制提交，因此，TCC必须依赖各阶段（Try/Confirm/Cancel）的本地事务的原子性和一致性来实现全局事务的原子性和一致性。

## 三、ByteTCC为什么要基于TransactionManager的机制来实现TCC全局事务？
EJB/Spring容器的声明式事务处理机制都是将事务请求（begin、commit、rollback等）委托给TransactionManager来完成，因此TCC各阶段的service执行的结果生效与否（commit/rollback），从TransactionManager的底层角度就可以有比较准确的判断。

相反，如果从应用系统层面/service层面根据注解、配置、异常等信息判断本地事务是否提交，则效果要差的多，并且也非常复杂。EJB/Spring都将异常定义成系统异常、应用异常两类异常，不同类型的异常对应有不同的事务完成策略，且两类异常还可通过注解/配置调整/追加，如spring可通过Transactional.rollbackFor/noRollbackFor来配置什么异常应该回滚什么异常不应该回滚。可见，service执行抛出异常时，并不能说明事务被回滚了。更有甚者，部分容器（比如EJB）还允许业务代码通过Context来设置自己希望的事务完成方向（如EJBContext.setRollbackOnly）。所以，如果仅从应用系统层面/service层面来判断本地事务状态，是不能保证准确的。

## 四、关于幂等性
ByteTCC不要求service的实现逻辑具有幂等性。事实上，ByteTCC也不推荐这样做，因为在业务层面实现幂等性，其复杂度非常高。因此ByteTCC在实现时也做了这方面的考虑。ByteTCC在补偿TCC事务时，虽然也可能会多次调用confirm/cancel方法，但是ByteTCC可以确保每个confirm/cancel方法仅被"执行并提交"一次，所以，使用ByteTCC时可以仅关注业务逻辑，而不必考虑事务相关的细节。

#### “仅执行并提交一次”的说明：
* 1、Confirm操作虽然可能被多次调用，但是其参与的LocalTransaction均由ByteTCC事务管理器控制，一旦Confirm操作所在的LocalTransaction事务被ByteTCC事务管理器成功提交，则ByteTCC事务管理器会标注该Confirm操作成功，后续将不再执行该Confirm操作。
* 2、Cancel操作的控制原理同Confirm操作。需要说明的是，Cancel操作只有在Try阶段所在的LocalTransaction被成功提交的情况下才会被调用，Try阶段所在的LocalTransaction被回滚时Cancel操作不会被执行。

## 五、ByteTCC中TCC机制与事务补偿机制的异同
#### 5.1、相同之处
* 1、都为业务操作提供一个补偿操作，该操作在全局事务决定回滚的情况下被调用；

#### 5.2、不同之处
* 1、TCC机制提供一个Confirm操作，该操作在全局事务决定提交的情况下被调用。需要说明的是，（ByteTCC更倾向于）Confirm只是一个可选的阶段，即不是每个业务都需要一个确认的逻辑。在没有指定Confirm操作的情况下，TCC即等同于事务补偿机制。

## 六、ByteTCC中TCC事务与普通事务的异同
#### 6.1、相同之处
* 1、均使用相同的相同的配置、相同的事务管理器；
* 2、均通过@Transactional注解来声明事务，在事务传播（propagation）、异常回滚等方面，二者均使用相同的语义；

#### 6.2、不同之处
* 1、使用TCC事务时，需要为service标注@Compensable注解，并指定Confirm/Cancel逻辑的service实现（Confirm非必需，如果没有确认逻辑，也可不指定）；

## 七、ByteTCC特性
* 1、支持Spring容器的声明式事务管理；
* 2、同时支持XA、TCC两种机制。应用开发者根据实际需要，可以单独使用XA事务或TCC事务，也可以同时使用XA、TCC两种机制的事务；
* 3、支持多数据源、多应用、多服务器事务传播的分布式事务协调，满足多种不同的事务处理的需求；
* 4、支持出错事务恢复。

## 八、当前版本
#### 0.3.0-SNAPSHOT主要目标
* 1、精简TCC事务管理器的处理逻辑；
* 2、新增对dubbo框架的支持。

## 九、已发布的历史版本
##### 0.1.2
* 地址：http://code.google.com/p/bytetcc

##### 0.2.0-alpha
* 地址：http://code.taobao.org/p/openjtcc
* 文档：http://code.taobao.org/p/openjtcc/wiki/index/

## 十、建议及改进
0.3.0-SNAPSHOT版本目前仍在开发中，若您有任何建议，可以通过1）加入qq群537445956向群主提出，或2）发送邮件至bytefox@126.com向我反馈。本人承诺，任何建议都将会被认真考虑，优秀的建议将会被采用，但不保证一定会在当前版本中实现。
