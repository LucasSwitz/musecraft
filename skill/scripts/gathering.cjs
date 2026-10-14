// Native MineProcess owns discovery, tool use, navigation, mining and drop pickup.
module.exports=async({args,io,progress})=>require('./native-task.cjs')({kind:'gather',args,io,progress})
