ByteTCC是一个基于TCC（Try/Confirm/Cancel）机制的分布式事务管理器。兼容JTA，可以很好的与EJB、Spring等容器（本文档下文说明中将以Spring容器为例）进行集成。

## 一、Try/Confirm/Cancel模式
// TODO

## 二、事务补偿模式
// TODO

## 三、TCC模式与事务补偿模式的异同
#### 3.1、相同之处
// TODO

#### 3.2、不同之处
// TODO

## 四、ByteTCC中TCC事务与普通事务的异同
#### 4.1、相同之处
* 1、均使用相同的相同的配置、相同的事务管理器；
* 2、均通过@Transactional注解来声明事务，在事务传播（propagation）、异常回滚等方面，二者均使用相同的语义；

#### 4.2、不同之处
* 1、使用TCC事务时，需要为service标注@Compensable注解，并指定Confirm/Cancel逻辑的service实现（Confirm非必需，如果没有确认逻辑，也可不指定）；

## 五、ByteTCC特性
* 1、支持Spring容器的声明式事务管理；
* 2、支持普通事务、TCC事务、业务补偿型事务等事务机制；
* 3、支持多数据源、多应用、多服务器事务传播的分布式事务协调，满足多种不同的事务处理的需求；
* 4、支持dubbo服务框架；

## 六、当前版本
#### 0.3.0-alpha主要目标
* 1、新增对dubbo框架的支持；
* 2、精简TCC事务管理器的处理逻辑；

#### 0.3.0-alpha样例
* 地址： https://github.com/liuyangming/ByteTCC-sample
* 文档： https://github.com/liuyangming/ByteTCC/wiki

## 七、已发布的历史版本
##### 0.1.2
* 地址：http://code.google.com/p/bytetcc

##### 0.2.0-alpha
* 地址：http://code.taobao.org/p/openjtcc
* 文档：http://code.taobao.org/p/openjtcc/wiki/index/

## 八、建议及改进
若您有任何建议，可以通过1）加入qq群537445956向群主提出，或2）发送邮件至bytefox@126.com向我反馈。本人承诺，任何建议都将会被认真考虑，优秀的建议将会被采用，但不保证一定会在当前版本中实现。
