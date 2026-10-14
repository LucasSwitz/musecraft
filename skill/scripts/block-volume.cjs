// A radius selects whole cells on each axis, including both boundary blocks.
const block = point => point.map(Math.floor)
const contains = (area, point) => block(point).every((v,i)=>Math.abs(v-Math.floor(area.near[i]))<=area.radius)
module.exports={block,contains}
