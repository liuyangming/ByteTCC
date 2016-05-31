# ByteTCC
ByteTCC是一个基于TCC（Try/Confirm/Cancel）事务补偿机制的分布式事务管理器，兼容JTA，因此可以很好的与EJB、Spring等容器（本文档下文说明中将以Spring容器为例）进行集成，支持Spring容器的声明式事务。

ByteTCC事务管理器同时支持JTA、TCC两种机制，应用开发者根据实际需要使用JTA事务管理器或TCC事务管理器，甚至可以同时使用基于XA、TCC两种机制的事务。

ByteTCC通过为service标注Compensable注解来区分普通事务和TCC事务，将未标注Compensable注解的service使用普通事务，将标注Compensable注解的service使用TCC事务，其他则完全一样，应用开发者无需对事务处理细节了解太多即可使用。

ByteTCC支持多数据源、多应用、多服务器事务传播的分布式事务协调，满足多种不同的事务处理的需求。

0.2.1版本特性：新增对dubbo的支持。

### 已发布的历史版本
###### 0.1.2
地址：http://code.google.com/p/bytetcc

###### 0.2.0-alpha
地址：http://code.taobao.org/p/openjtcc
文档：http://code.taobao.org/p/openjtcc/wiki/index/
